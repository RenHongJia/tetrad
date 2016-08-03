package edu.cmu.tetrad.algcomparison.simulation;

import edu.cmu.tetrad.algcomparison.graph.RandomGraph;
import edu.cmu.tetrad.algcomparison.graph.SingleGraph;
import edu.cmu.tetrad.algcomparison.utils.Parameters;
import edu.cmu.tetrad.bayes.BayesIm;
import edu.cmu.tetrad.bayes.BayesPm;
import edu.cmu.tetrad.bayes.MlBayesIm;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.data.DataType;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.sem.SemIm;
import edu.cmu.tetrad.sem.SemPm;
import edu.cmu.tetrad.sem.StandardizedSemIm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author jdramsey
 */
public class SemSimulation implements Simulation {
    private RandomGraph randomGraph;
    private SemPm pm;
    private SemIm im;
    private StandardizedSemIm standardizedIm;
    private List<DataSet> dataSets;
    private Graph graph;

    public SemSimulation(RandomGraph graph) {
        this.randomGraph = graph;
    }

    public SemSimulation(SemPm pm) {
        this.randomGraph = new SingleGraph(pm.getGraph());
        this.pm = pm;
    }

    public SemSimulation(SemIm im) {
        this.randomGraph = new SingleGraph(im.getSemPm().getGraph());
        this.im = im;
    }

    public SemSimulation(StandardizedSemIm im) {
        this.standardizedIm = im;
    }

    @Override
    public void createData(edu.cmu.tetrad.algcomparison.utils.Parameters parameters) {
        this.graph = randomGraph.createGraph(parameters);

        dataSets = new ArrayList<>();

        for (int i = 0; i < parameters.getInt("numRuns", 1); i++) {
            System.out.println("Simulating dataset #" + (i + 1));
            DataSet dataSet = simulate(graph, parameters);
            dataSets.add(dataSet);
        }
    }

    @Override
    public DataSet getDataSet(int index) {
        return dataSets.get(index);
    }

    @Override
    public Graph getTrueGraph() {
        return graph;
    }

    @Override
    public String getDescription() {
        return "Linear, Gaussian SEM simulation using " + randomGraph.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = randomGraph.getParameters();
        parameters.put("numRuns", 1);
        parameters.put("sampleSize", 1000);
        parameters.put("variance", 1.0);
        return parameters;
    }

    @Override
    public int getNumDataSets() {
        return dataSets.size();
    }

    @Override
    public DataType getDataType() {
        return DataType.Continuous;
    }

    private DataSet simulate(Graph graph, Parameters parameters) {
        SemIm im = this.im;

        if (standardizedIm != null) {
            return standardizedIm.simulateData(parameters.getInt("sampleSize", 1000), false);
        } else {
            if (im == null) {
                SemPm pm = this.pm;

                if (pm == null) {
                    pm = new SemPm(graph);
                }

                im = new SemIm(pm);
            }

            return im.simulateData(parameters.getInt("sampleSize", 1000), false);
        }
    }
}
