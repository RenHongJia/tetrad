///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.Node;
import edu.cmu.tetrad.util.DepthChoiceGenerator;
import edu.cmu.tetrad.util.TetradMatrix;
import edu.cmu.tetrad.util.TetradVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularMatrixException;
import org.apache.commons.math3.util.FastMath;

import java.io.PrintStream;
import java.util.*;

import static edu.cmu.tetrad.util.StatUtils.*;
import static java.lang.Math.*;

/**
 * Implements the continuous BIC score for FGES.
 *
 * @author Joseph Ramsey
 */
public class SemBicScore implements Score {

    private double[] k1s;
    private double[] k2s;
    private double[] k3s = null;
    private double[] k4s = null;
    private double[] k5s = null;
    private double[] k6s = null;
    // The covariance matrix.
    private ICovarianceMatrix covariances;

    // The variables of the covariance matrix.
    private List<Node> variables;

    // The sample size of the covariance matrix.
    private int sampleSize;

    // The penalty penaltyDiscount.
    private double penaltyDiscount = 1.0;

    // True if linear dependencies should return NaN for the score, and hence be
    // ignored by FGES
    private boolean ignoreLinearDependent = false;

    // The printstream output should be sent to.
    private PrintStream out = System.out;

    // True if verbose output should be sent to out.
    private boolean verbose = false;

    // Variables that caused computational problems and so are to be avoided.
    private Set<Integer> forbidden = new HashSet<>();

    private Map<String, Integer> indexMap;

    private RecursivePartialCorrelation recursivePartialCorrelation;

    private double structurePrior = 0.0;
    private double delta = 0.0;


    /**
     * Constructs the score using a covariance matrix.
     */
    public SemBicScore(ICovarianceMatrix covariances) {
        if (covariances == null) {
            throw new NullPointerException();
        }

        setCovariances(covariances);
        this.variables = covariances.getVariables();
        this.sampleSize = covariances.getSampleSize();
        this.indexMap = indexMap(this.variables);
        this.recursivePartialCorrelation = new RecursivePartialCorrelation(covariances);
    }

    /**
     * Constructs the score using a covariance matrix.
     */
    public SemBicScore(DataSet dataSet, boolean kevin) {
        if (dataSet == null) {
            throw new NullPointerException();
        }

        dataSet = DataUtils.center(dataSet);

        ICovarianceMatrix cov = dataSet instanceof ICovarianceMatrix ? (ICovarianceMatrix) dataSet
                : new CovarianceMatrix(dataSet, kevin);

        setCovariances(cov);

        setCovariances(covariances);
        this.variables = covariances.getVariables();
        this.sampleSize = covariances.getSampleSize();
        this.indexMap = indexMap(this.variables);
        this.recursivePartialCorrelation = new RecursivePartialCorrelation(covariances);


        DataSet _d = dataSet;
//
        double[][] __d = _d.getDoubleData().transpose().toArray();

        this.k1s = new double[__d.length];
        for (int i = 0; i < __d.length; i++) k1s[i] = k1(__d[i]);

        this.k2s = new double[__d.length];
        for (int i = 0; i < __d.length; i++) k2s[i] = k2(__d[i]);

        this.k3s = new double[__d.length];
        for (int i = 0; i < __d.length; i++) k3s[i] = k3(__d[i]);

        this.k4s = new double[__d.length];
        for (int i = 0; i < __d.length; i++) k4s[i] = k4(__d[i]);

        this.k5s = new double[__d.length];
        for (int i = 0; i < __d.length; i++) k5s[i] = k5(__d[i]);

        this.k6s = new double[__d.length];
        for (int i = 0; i < __d.length; i++) k6s[i] = k6(__d[i]);
    }

    private double k1(double[] x) {
        return 0;// mu(1, x);
    }

    private double k2(double[] x) {
        return mu(2, x);// - pow(mu(1, x), 2.0);
    }

    private double k3(double[] x) {
        return mu(3, x);// - 3.0 * mu(2, x) * mu(1, x) + 2.0 * pow(mu(1, x), 3.0);
    }

    private double k4(double[] x) {
        return mu(4, x)/* - 4 * mu(3, x) * mu(1, x)*/ - 3 * pow(mu(2, x), 2)/* + 12* (mu(2, x) * pow(mu(1, x), 2))
                - 6 * pow(mu(1, x), 4)*/;
    }

    private double k5(double[] x) {
        return mu(5, x) - 10 * mu(3, x) * mu(2, x);
    }

    private double k6(double[] x) {
        return mu(6, x) - 15 * mu(4, x) * mu(2, x) - 10 * pow(mu(3, x), 2) + 30 * pow(mu(2, x), 3);
    }

    private double mu(int p, double[] x) {
//        double avg = 0;
//
//        for (double v : x) {
//            avg += v;
//        }
//
//        avg /= x.length;

        if (p == 0) return 0;
        if (p == 1) return 1;

        double sum = 0;

        for (double v : x) {
            sum += pow(v, p);// - avg, p);
        }
        ;
        return sum / x.length;
    }

    /**
     * Calculates the sample likelihood and BIC score for i given its parents in a simple SEM model
     */
    public double localScore(int i, int... parents) {
        for (int p : parents) if (forbidden.contains(p)) return Double.NaN;

        try {
            double s2 = getCovariances().getValue(i, i);
            final int p = parents.length;
            int k = p + 1;

            TetradMatrix covxx = getCovariances().getSelection(parents, parents);
            TetradVector covxy = (getCovariances().getSelection(parents, new int[]{i})).getColumn(0);
            TetradVector coefs = (covxx.inverse()).times(covxy);

            s2 -= coefs.dotProduct(covxy);


            if (s2 <= 0) {
                if (isVerbose()) {
                    out.println("Nonpositive residual varianceY: resVar / varianceY = " + (s2 / getCovariances().getValue(i, i)));
                }

                return Double.NaN;
            }

            double _k1 = k1s[i];

            for (int t = 0; t < coefs.size(); t++) {
                _k1 -= pow(coefs.get(t), 1) * k1s[parents[t]];
            }

            double _k2 = k2s[i];

            for (int t = 0; t < coefs.size(); t++) {
                _k2 -= pow(coefs.get(t), 2) * k2s[parents[t]];
            }

            double _k3 = k3s[i];

            for (int t = 0; t < coefs.size(); t++) {
                _k3 -= pow(coefs.get(t), 3) * k3s[parents[t]];
            }

            double _k4 = k4s[i];

            for (int t = 0; t < coefs.size(); t++) {
                _k4 -= pow(coefs.get(t), 4) * k4s[parents[t]];
            }

            double _k5 = k5s[i];

            for (int t = 0; t < coefs.size(); t++) {
                _k5 -= pow(coefs.get(t), 5) * k5s[parents[t]];
            }

            double _k6 = k6s[i];

            for (int t = 0; t < coefs.size(); t++) {
                _k6 -= pow(coefs.get(t), 6) * k6s[parents[t]];
            }

            double stk3 = abs(_k3 / pow(_k2, 1.5));

//            System.out.println("stk3 = " + stk3);

            double stk4 = _k4 / pow(_k2, 2);

//            s2 = _k2;

            double stk5 = _k5 / pow(_k2, 2.5);

            double stk6 = _k6 / pow(_k2, 3);

            double n = getSampleSize();

//            s2 += .01 * p * p * tanh(pow(stk3, 2) + pow(stk4, 2) + pow(stk5, 2));
            double q = p * (p + 1);// / 2;
            s2 += p * getDelta() * (1. / n) * (stk4 / 6);// -stk4 + abs(stk5));


//            s2 += 3 * p * getDelta() * (1. / n) * (abs(stk3));// -stk4 + abs(stk5));

//            return -n * log(s2) - k * log(n);// - log((abs(stk3) * abs(stk4)));// * abs(stk5)));
            return -n * log(s2) /*- getDelta() * (3 * abs(stk4))*/ - getPenaltyDiscount() * k * log(n) + getStructurePrior(parents.length);
        } catch (Exception e) {
            boolean removedOne = true;

            while (removedOne) {
                List<Integer> _parents = new ArrayList<>();
                for (int parent : parents) _parents.add(parent);
                _parents.removeAll(forbidden);
                parents = new int[_parents.size()];
                for (int y = 0; y < _parents.size(); y++) parents[y] = _parents.get(y);
                removedOne = printMinimalLinearlyDependentSet(parents, getCovariances());
            }

            return Double.NaN;
        }
    }

    private static double LOG2PI = log(2.0 * Math.PI);

    // One record.
    private double gaussianLikelihood(int k, TetradMatrix sigma) {
        return -0.5 * logdet(sigma) - 0.5 * k * (1 + LOG2PI);
    }

    private double logdet(TetradMatrix m) {
        if (m.columns() == 0) {
            return 1;
        }

        RealMatrix M = m.getRealMatrix();
        final double tol = 1e-9;
        RealMatrix LT = new org.apache.commons.math3.linear.CholeskyDecomposition(M, tol, tol).getLT();

        double sum = 0.0;

        for (int i = 0; i < LT.getRowDimension(); i++) {
            sum += FastMath.log(LT.getEntry(i, i));
        }

        return 2.0 * sum;
    }

    private double getStructurePrior(int parents) {
        if (getStructurePrior() <= 0) {
            return 0;
        } else {
            int c = covariances.getDimension();
            double p = structurePrior / (double) c;
            return (parents * Math.log(p) + (c - parents) * Math.log(1.0 - p));
        }
    }

    @Override
    public double localScoreDiff(int x, int y, int[] z) {

//        Node _x = variables.get(x);
//        Node _y = variables.get(y);
//        List<Node> _z = getVariableList(z);
//
//        double r = partialCorrelation(_x, _y, _z);
//
////        double sp1 = getStructurePrior(z.length + 1);
////        double sp2 = getStructurePrior(z.length);
//
//
//        int n = covariances.getSampleSize();
//        return -n * Math.log(1.0 - r * r) - getPenaltyDiscount() * Math.log(n);// + sp1 - sp2;
//
        return localScore(y, append(z, x)) - localScore(y, z);
    }

    private List<Node> getVariableList(int[] indices) {
        List<Node> variables = new ArrayList<>();
        for (int i : indices) {
            variables.add(this.variables.get(i));
        }
        return variables;
    }

    private double partialCorrelation(Node x, Node y, List<Node> z) throws SingularMatrixException {
        return this.recursivePartialCorrelation.corr(x, y, z);
//        int[] indices = new int[z.size() + 2];
//        indices[0] = indexMap.get(x.getName());
//        indices[1] = indexMap.get(y.getName());
//        for (int i = 0; i < z.size(); i++) indices[i + 2] = indexMap.get(z.get(i).getName());
//        TetradMatrix submatrix = covariances.getSubmatrix(indices).getMatrix();
//        return StatUtils.partialCorrelationWhittaker(submatrix);
    }

    private Map<String, Integer> indexMap(List<Node> variables) {
        Map<String, Integer> indexMap = new HashMap<>();

        for (int i = 0; i < variables.size(); i++) {
            indexMap.put(variables.get(i).getName(), i);
        }

        return indexMap;
    }

    @Override
    public double localScoreDiff(int x, int y) {
        return localScoreDiff(x, y, new int[0]);
    }

    private int[] append(int[] parents, int extra) {
        int[] all = new int[parents.length + 1];
        System.arraycopy(parents, 0, all, 0, parents.length);
        all[parents.length] = extra;
        return all;
    }

    /**
     * Specialized scoring method for a single parent. Used to speed up the effect edges search.
     */
    public double localScore(int i, int parent) {
        return localScore(i, new int[]{parent});

//        double residualVariance = getCovariances().getValue(i, i);
//        int n = getSampleSize();
//        int p = 1;
//        final double covXX = getCovariances().getValue(parent, parent);
//
//        if (covXX == 0) {
//            if (isVerbose()) {
//                out.println("Dividing by zero");
//            }
//            return Double.NaN;
//        }
//
//        double covxxInv = 1.0 / covXX;
//        double covxy = getCovariances().getValue(i, parent);
//        double b = covxxInv * covxy;
//        residualVariance -= covxy * b;
//
//        if (residualVariance <= 0) {
//            if (isVerbose()) {
//                out.println("Nonpositive residual varianceY: resVar / varianceY = " + (residualVariance / getCovariances().getValue(i, i)));
//            }
//            return Double.NaN;
//        }
//
//        return score(residualVariance, n, p);
    }

    /**
     * Specialized scoring method for no parents. Used to speed up the effect edges search.
     */
    public double localScore(int i) {
        return localScore(i, new int[0]);
//        double residualVariance = getCovariances().getValue(i, i);
//        int n = getSampleSize();
//        int p = 0;
//
//        if (residualVariance <= 0) {
//            if (isVerbose()) {
//                out.println("Nonpositive residual varianceY: resVar / varianceY = " + (residualVariance / getCovariances().getValue(i, i)));
//            }
//            return Double.NaN;
//        }
//
//        double c = getPenaltyDiscount();
//        return score(residualVariance, n, p);
    }

    /**
     * True iff edges that cause linear dependence are ignored.
     */
    public boolean isIgnoreLinearDependent() {
        return ignoreLinearDependent;
    }

    public void setIgnoreLinearDependent(boolean ignoreLinearDependent) {
        this.ignoreLinearDependent = ignoreLinearDependent;
    }

    public void setOut(PrintStream out) {
        this.out = out;
    }

    public double getPenaltyDiscount() {
        return penaltyDiscount;
    }

    public ICovarianceMatrix getCovariances() {
        return covariances;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    @Override
    public boolean isEffectEdge(double bump) {
        return bump > 0;// -.1 * getPenaltyDiscount() * Math.log(sampleSize);
    }

    public DataSet getDataSet() {
        throw new UnsupportedOperationException();
    }

    public void setPenaltyDiscount(double penaltyDiscount) {
        this.penaltyDiscount = penaltyDiscount;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public List<Node> getVariables() {
        return variables;
    }

    // Prints a smallest subset of parents that causes a singular matrix exception.
    private boolean printMinimalLinearlyDependentSet(int[] parents, ICovarianceMatrix cov) {
        List<Node> _parents = new ArrayList<>();
        for (int p : parents) _parents.add(variables.get(p));

        DepthChoiceGenerator gen = new DepthChoiceGenerator(_parents.size(), _parents.size());
        int[] choice;

        while ((choice = gen.next()) != null) {
            int[] sel = new int[choice.length];
            List<Node> _sel = new ArrayList<>();
            for (int m = 0; m < choice.length; m++) {
                sel[m] = parents[m];
                _sel.add(variables.get(sel[m]));
            }

            TetradMatrix m = cov.getSelection(sel, sel);

            try {
                m.inverse();
            } catch (Exception e2) {
                forbidden.add(sel[0]);
                out.println("### Linear dependence among variables: " + _sel);
                out.println("### Removing " + _sel.get(0));
                return true;
            }
        }

        return false;
    }

    private void setCovariances(ICovarianceMatrix covariances) {
        this.covariances = covariances;
    }

    public void setVariables(List<Node> variables) {
        covariances.setVariables(variables);
        this.variables = variables;
    }

    @Override
    public Node getVariable(String targetName) {
        for (Node node : variables) {
            if (node.getName().equals(targetName)) {
                return node;
            }
        }

        return null;
    }

    @Override
    public int getMaxDegree() {
        return (int) Math.ceil(log(sampleSize));
    }

    @Override
    public boolean determines(List<Node> z, Node y) {
        int i = variables.indexOf(y);

        int[] k = new int[z.size()];

        for (int t = 0; t < z.size(); t++) {
            k[t] = variables.indexOf(z.get(t));
        }

        double v = localScore(i, k);

        return Double.isNaN(v);
    }

    public double getStructurePrior() {
        return structurePrior;
    }

    public void setStructurePrior(double structurePrior) {
        this.structurePrior = structurePrior;
    }

    public double getDelta() {
        return delta;
    }

    public void setDelta(double delta) {
        this.delta = delta;
    }
}



