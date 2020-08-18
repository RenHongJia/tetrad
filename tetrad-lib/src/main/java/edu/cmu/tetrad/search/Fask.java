///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (c) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
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

import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.data.DataUtils;
import edu.cmu.tetrad.data.IKnowledge;
import edu.cmu.tetrad.data.Knowledge2;
import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.regression.RegressionDataset;
import edu.cmu.tetrad.regression.RegressionResult;
import edu.cmu.tetrad.util.TetradLogger;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;

import static edu.cmu.tetrad.util.StatUtils.*;
import static java.lang.Math.*;

/**
 * Runs the FASK (Fast Adjacency Skewness) algorithm. The reference is Sanchez-Romero, R., Ramsey, J. D.,
 * Zhang, K., Glymour, M. R., Huang, B., & Glymour, C. (2019). Estimating feedforward and feedback
 * effective connections from fMRI time series: Assessments of statistical methods. Network Neuroscience,
 * 3(2), 274-306, though it has been improved in some ways from that version, and some pairwise methods from
 * Hyvärinen, A., & Smith, S. M. (2013). Pairwise likelihood ratios for estimation of non-Gaussian structural
 * equation models. Journal of Machine Learning Research, 14(Jan), 111-152 have been included for
 * comparison (and potential use!--they are quite good!).
 * <p>
 * This method (and the Hyvarinen and Smith methods) make the assumption that the data are generated by
 * a linear, non-Gaussian causal process and attempts to recover the causal graph for that process. They
 * do not attempt to recover the parametrization of this graph; for this a separate estimation algorithm
 * would be needed, such as linear regression regressing each node onto its parents. A further assumption
 * is made, that there are no latent common causes of the algorithm. This is not a constraint on the pairwise
 * orientation methods, since they orient with respect only to the two variables at the endpoints of an edge
 * and so are happy with all other variables being considered latent with respect to that single edge. However,
 * if the built-in adjacency search is used (FAS-Stable), the existence of latents will throw this method
 * off.
 * <p>
 * As was shown in the Hyvarinen and Smith paper above, FASK works quite well even if the graph contains
 * feedback loops in most configurations, including 2-cycles. 2-cycles can be detected fairly well if the
 * FASK left-right rule is selected and the 2-cycle threshold set to about 0.1--more will be detected (or
 * hallucinated) if the threshold is set higher. As shown in the Sanchez-Romero reference above, 2-cycle
 * detection of the FASK algorithm using this rule is quite good.
 * <p>
 * Some edges may be undiscoverable by FAS-Stable; to recover more of these edges, a test related to the
 * FASK left-right rule is used, and there is a threshold for this test. A good default for this threshold
 * (the "skew edge threshold") is 0.3. For more of these edges, set this threshold to a lower number.
 * <p>
 * It is assumed that the data are arranged so the each variable forms a column and that there are no missing
 * values. The data matrix is assumed to be rectangular. To this end, the Tetrad DataSet class is used, which
 * enforces this.
 *
 * @author Joseph Ramsey
 */
public final class Fask implements GraphSearch {

    // The left-right rule to use. Options include the FASK left-right rule and three left-right rules
    // from the Hyvarinen and Smith pairwise orientation paper: Robust Skew, Skew, and Tanh. In that
    // paper, "empirical" versions were given in which the variables are multiplied through by the
    // signs of the skewnesses; we follow this advice here (with good results). These others are provided
    // for comparison; in general they are quite good.
    public enum LeftRight {FASK, RSKEW, SKEW, TANH}

    // The score to be used for the FAS adjacency search.
    private final IndependenceTest test;

    // An initial graph to constrain the adjacency step.
    private Graph initialGraph = null;

    // Elapsed time of the search, in milliseconds.
    private long elapsed = 0;

    // The data sets being analyzed. They must all have the same variables and the same
    // number of records.
    private final DataSet dataSet;

    // For the Fast Adjacency Search, the maximum number of edges in a conditioning set.
    private int depth = -1;

    // Knowledge the the search will obey, of forbidden and required edges.
    private IKnowledge knowledge = new Knowledge2();

    // A threshold for including extra adjacencies due to skewness. Default is 0.3. For more edges, lower
    // this threshold.
    private double skewEdgeThreshold = 0;

    // A theshold for making 2-cycles. Default is 0 (no 2-cycles.) Note that the 2-cycle rule will only work
    // with the FASK left-right rule. Default is 0; a good value for finding a decent set of 2-cycles is 0.1.
    private double twoCycleThreshold = 0;

    // True if FAS adjacencies should be included in the output, by default true.
    private boolean useFasAdjacencies = true;

    // The left right rule to use, default FASK.
    private LeftRight leftRight = LeftRight.RSKEW;

    // The graph resulting from search.
    private Graph graph;

    // Used for calculating coefficient values.
    private final RegressionDataset regressionDataset;

    /**
     * @param dataSet A continuous dataset over variables V.
     * @param test    An independence test over variables V. (Used for FAS.)
     */
    public Fask(DataSet dataSet, IndependenceTest test) {
        if (!dataSet.isContinuous()) {
            throw new IllegalArgumentException("For FASK, the dataset must be entirely continuous");
        }

        this.dataSet = dataSet;
        this.test = test;

        regressionDataset = new RegressionDataset(dataSet);

    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * Runs the search on the concatenated data, returning a graph, possibly cyclic, possibly with
     * two-cycles. Runs the fast adjacency search (FAS, Spirtes et al., 2000) follows by a modification
     * of the robust skew rule (Pairwise Likelihood Ratios for Estimation of Non-Gaussian Structural
     * Equation Models, Smith and Hyvarinen), together with some heuristics for orienting two-cycles.
     *
     * @return the graph. Some of the edges may be undirected (though it shouldn't be many in most cases)
     * and some of the adjacencies may be two-cycles.
     */
    public Graph search() {
        long start = System.currentTimeMillis();
        NumberFormat nf = new DecimalFormat("0.000");

        DataSet dataSet = DataUtils.standardizeData(this.dataSet);

        List<Node> variables = dataSet.getVariables();
        double[][] colData = dataSet.getDoubleData().transpose().toArray();

        TetradLogger.getInstance().forceLogMessage("FASK v. 2.0");
        TetradLogger.getInstance().forceLogMessage("");
        TetradLogger.getInstance().forceLogMessage("# variables = " + dataSet.getNumColumns());
        TetradLogger.getInstance().forceLogMessage("N = " + dataSet.getNumRows());
        TetradLogger.getInstance().forceLogMessage("Skewness edge threshold = " + skewEdgeThreshold);
        TetradLogger.getInstance().forceLogMessage("2-cycle threshold = " + twoCycleThreshold);
        TetradLogger.getInstance().forceLogMessage("");

        Graph G0;

        if (isUseFasAdjacencies()) {
            FasStable fas = new FasStable(test);
            fas.setDepth(getDepth());
            fas.setVerbose(false);
            fas.setKnowledge(knowledge);
            G0 = fas.search();
        } else if (getInitialGraph() != null) {
            TetradLogger.getInstance().forceLogMessage("Using initial graph.");

            Graph g1 = new EdgeListGraph(getInitialGraph().getNodes());

            for (Edge edge : getInitialGraph().getEdges()) {
                Node x = edge.getNode1();
                Node y = edge.getNode2();

                if (!g1.isAdjacentTo(x, y)) g1.addUndirectedEdge(x, y);
            }

            g1 = GraphUtils.replaceNodes(g1, dataSet.getVariables());

            G0 = g1;
        } else {
            G0 = new EdgeListGraph(dataSet.getVariables());
        }

        G0 = GraphUtils.replaceNodes(G0, dataSet.getVariables());

        TetradLogger.getInstance().forceLogMessage("");

        assert G0 != null;
        SearchGraphUtils.pcOrientbk(knowledge, G0, G0.getNodes());

        Graph graph = new EdgeListGraph(G0.getNodes());

        TetradLogger.getInstance().forceLogMessage("X\tY\tMethod\tLR\tEdge");

        int V = variables.size();

        for (int i = 0; i < V; i++) {
            for (int j = 0; j < V; j++) {
                if (i == j) continue;

                Node X = variables.get(i);
                Node Y = variables.get(j);

                if (graph.isAdjacentTo(X, Y)) continue;

                // Centered
                double[] x = colData[i];
                double[] y = colData[j];

                double c1 = correxp(x, y, x);
                double c2 = correxp(x, y, y);

                if (!(isUseFasAdjacencies() && G0.isAdjacentTo(X, Y))
                        && (initialGraph == null || !initialGraph.isAdjacentTo(X, Y))
                        && (abs(c1 - c2) < skewEdgeThreshold)) {
                    continue;
                }

                double lrxy = leftRight(x, y);

                if (edgeForbiddenByKnowledge(X, Y)) {
                    TetradLogger.getInstance().forceLogMessage(X + "\t" + Y + "\tknowledge_forbidden"
                            + "\t" + nf.format(lrxy)
                            + "\t" + X + "<->" + Y
                    );
                    continue;
                }

                if (knowledgeOrients(X, Y)) {
                    TetradLogger.getInstance().forceLogMessage(X + "\t" + Y + "\tknowledge"
                            + "\t" + nf.format(lrxy)
                            + "\t" + X + "-->" + Y
                    );
                    graph.addDirectedEdge(X, Y);
                } else if (knowledgeOrients(Y, X)) {
                    TetradLogger.getInstance().forceLogMessage(X + "\t" + Y + "\tknowledge"
                            + "\t" + nf.format(lrxy)
                            + "\t" + X + "<--" + Y
                    );
                    graph.addDirectedEdge(Y, X);
                } else if (abs(lrxy) < twoCycleThreshold) {
                    TetradLogger.getInstance().forceLogMessage(X + "\t" + Y + "\t2-cycle"
                            + "\t" + nf.format(lrxy)
                            + "\t" + X + "<=>" + Y
                    );
                    graph.addDirectedEdge(X, Y);
                    graph.addDirectedEdge(Y, X);
                } else {
                    if (lrxy > 0) {
                        TetradLogger.getInstance().forceLogMessage(X + "\t" + Y + "\tleft-right"
                                + "\t" + nf.format(lrxy)
                                + "\t" + X + "-->" + Y
                        );
                        graph.addDirectedEdge(X, Y);
                    } else {
                        TetradLogger.getInstance().forceLogMessage(Y + "\t" + X + "\tleft-right"
                                + "\t" + nf.format(lrxy)
                                + "\t" + Y + "-->" + X
                        );
                        graph.addDirectedEdge(Y, X);
                    }
                }
            }
        }

        long stop = System.currentTimeMillis();
        this.elapsed = stop - start;

        this.graph = graph;

        return graph;
    }

    /**
     * Returns the coefficient matrix for the search. If the search has not yet run, runs it,
     * then estimates coefficients of each node given its parents using linear regression and forms
     * the B matrix of coefficients from these estimates. B[i][j] means i->j with that coefficient.
     */
    public double[][] getB() {
        if (graph == null) search();

        List<Node> nodes = dataSet.getVariables();
        double[][] B = new double[nodes.size()][nodes.size()];

        for (int i = 0; i < nodes.size(); i++) {
            Node y = nodes.get(i);

            List<Node> pary = graph.getParents(y);
            RegressionResult result = regressionDataset.regress(y, pary);
            double[] coef = result.getCoef();

            for (int j = 0; j < pary.size(); j++) {
                B[nodes.indexOf(pary.get(j))][i] = coef[j + 1];
            }
        }

        return B;
    }

    /**
     * @return The depth of search for the Fast Adjacency Search (FAS).
     */
    public int getDepth() {
        return depth;
    }

    /**
     * @param depth The depth of search for the Fast Adjacency Search (S). The default is -1.
     *              unlimited. Making this too high may results in statistical errors.
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /**
     * @return The elapsed time in milliseconds.
     */
    public long getElapsedTime() {
        return elapsed;
    }

    /**
     * @return the current knowledge.
     */
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    /**
     * @param knowledge Knowledge of forbidden and required edges.
     */
    public void setKnowledge(IKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    public Graph getInitialGraph() {
        return initialGraph;
    }

    public void setInitialGraph(Graph initialGraph) {
        this.initialGraph = initialGraph;
    }

    public void setSkewEdgeThreshold(double skewEdgeThreshold) {
        this.skewEdgeThreshold = skewEdgeThreshold;
    }

    public boolean isUseFasAdjacencies() {
        return useFasAdjacencies;
    }

    public void setUseFasAdjacencies(boolean useFasAdjacencies) {
        this.useFasAdjacencies = useFasAdjacencies;
    }

    public void setTwoCycleThreshold(double twoCycleThreshold) {
        this.twoCycleThreshold = twoCycleThreshold;
    }

    public void setLeftRight(LeftRight leftRight) {
        this.leftRight = leftRight;
    }

    //======================================== PRIVATE METHODS ====================================//

    private double leftRight(double[] x, double[] y) {
        if (leftRight == LeftRight.FASK) {
            return faskLeftRight(x, y);
        } else if (leftRight == LeftRight.RSKEW) {
            return robustSkew(x, y);
        } else if (leftRight == LeftRight.SKEW) {
            return skew(x, y);
        } else if (leftRight == LeftRight.TANH) {
            return tanh(x, y);
        }

        throw new IllegalStateException("Left right rule not configured: " + leftRight);
    }

    private double faskLeftRight(double[] x, double[] y) {
        double skx = skewness(x);
        double sky = skewness(y);
        double r = correlation(x, y);
        double lr = correxp(x, y, x) - correxp(x, y, y);
        return signum(skx) * signum(sky) * signum(r) * lr;
    }

    private double robustSkew(double[] x, double[] y) {
        x = correctSkewness(x, skewness(x));
        y = correctSkewness(y, skewness(y));

        double[] lr = new double[x.length];

        for (int i = 0; i < x.length; i++) {
            lr[i] = g(x[i]) * y[i] - x[i] * g(y[i]);
        }

        return correlation(x, y) * mean(lr);
    }

    private double skew(double[] x, double[] y) {
        x = correctSkewness(x, skewness(x));
        y = correctSkewness(y, skewness(y));

        double[] lr = new double[x.length];

        for (int i = 0; i < x.length; i++) {
            lr[i] = x[i] * x[i] * y[i] - x[i] * y[i] * y[i];
        }

        return correlation(x, y) * mean(lr);
    }

    private double tanh(double[] x, double[] y) {
        x = correctSkewness(x, skewness(x));
        y = correctSkewness(y, skewness(y));

        double[] lr = new double[x.length];

        for (int i = 0; i < x.length; i++) {
            lr[i] = x[i] * Math.tanh(y[i]) - Math.tanh(x[i]) * y[i];
        }

        return correlation(x, y) * mean(lr);
    }

    private double g(double x) {
        return Math.log(Math.cosh(Math.max(x, 0)));
    }

    private boolean knowledgeOrients(Node X, Node Y) {
        return knowledge.isForbidden(Y.getName(), X.getName()) || knowledge.isRequired(X.getName(), Y.getName());
    }

    private boolean edgeForbiddenByKnowledge(Node X, Node Y) {
        return knowledge.isForbidden(Y.getName(), X.getName()) && knowledge.isForbidden(X.getName(), Y.getName());
    }

    private static double correxp(double[] x, double[] y, double[] condition) {
        double exy = 0.0;
        double exx = 0.0;
        double eyy = 0.0;

        int n = 0;

        for (int k = 0; k < x.length; k++) {
            if (condition[k] > 0) {
                exy += x[k] * y[k];
                exx += x[k] * x[k];
                eyy += y[k] * y[k];
                n++;
            }
        }

        exy /= n;
        exx /= n;
        eyy /= n;

        return exy / sqrt(exx * eyy);
    }

    private double[] correctSkewness(double[] data, double sk) {
        double[] data2 = new double[data.length];
        for (int i = 0; i < data.length; i++) data2[i] = data[i] * Math.signum(sk);
        return data2;
    }
}






