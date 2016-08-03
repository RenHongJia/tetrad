package edu.cmu.tetrad.algcomparison.simulation;

import edu.cmu.tetrad.algcomparison.utils.HasParameters;
import edu.cmu.tetrad.algcomparison.utils.Parameters;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.data.DataType;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.util.TetradSerializable;

import java.util.List;
import java.util.Map;

/**
 * The interface that simulations must implement.
 *
 * @author jdramsey
 */
public interface Simulation extends HasParameters, TetradSerializable {

    /**
     * Creates a data set and simulates data.
     */
    void createData(Parameters parameters);

    /**
     * @return The number of data sets to simulate.
     */
    int getNumDataSets();

    /**
     * @return That graph.
     */
    Graph getTrueGraph();

    /**
     * @param index The index of the desired simulated data set.
     * @return That data set.
     */
    DataSet getDataSet(int index);

    /**
     * @return Returns the type of the data, continuous, discrete or mixed.
     */
    DataType getDataType();

    /**
     * @return Returns a one-line description of the simulation, to be printed
     * at the beginning of the report.
     */
    String getDescription();

    /**
     * @return Returns the parameters used in the simulation. These are the
     * parameters whose values can be varied.
     */
    Map<String, Object> getParameters();
}
