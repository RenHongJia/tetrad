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

import edu.cmu.tetrad.graph.*;
import edu.cmu.tetrad.util.ChoiceGenerator;
import edu.cmu.tetrad.util.DepthChoiceGenerator;

import java.awt.*;
import java.util.List;
import java.util.*;

import static java.util.Collections.addAll;

/**
 * Implements the PCP algorithm. The original idea for this was due to Eric Strobl and significantly revised by '
 * Wayne Lam and Peter Spirtes.
 *
 * @author Wayne Lam
 */
public class Pcp implements GraphSearch {

    /**
     * The independence test used for the PC search.g
     */
    private final IndependenceTest independenceTest;

    private double q = 1.0;

    private double fdr = Double.NaN;

    private double alphaStar = Double.NaN;

    private long elapsed = 0;

    //=============================CONSTRUCTORS==========================//

    /**
     * Constructs a new PC search using the given independence test as oracle.
     *
     * @param independenceTest The oracle for conditional independence facts. This does not make a copy of the
     *                         independence test, for fear of duplicating the data set!
     */
    public Pcp(IndependenceTest independenceTest) {
        if (independenceTest == null) {
            throw new NullPointerException();
        }

        this.independenceTest = independenceTest;
    }

    //==============================PUBLIC METHODS========================//

    /**
     * Runs PC starting with a complete graph over all nodes of the given conditional independence test, using the given
     * independence test and knowledge and returns the resultant graph. The returned graph will be a pattern if the
     * independence information is consistent with the hypothesis that there are no latent common causes. It may,
     * however, contain cycles or bidirected edges if this assumption is not born out, either due to the actual presence
     * of latent common causes, or due to statistical errors in conditional independence judgments.
     */
    public Graph search() {
        if (getIndependenceTest() == null) {
            throw new NullPointerException();
        }

        long start = System.currentTimeMillis();

        List<Node> nodes = getIndependenceTest().getVariables();
        double alpha = independenceTest.getAlpha();

        // algorithm 1

        Graph G1 = completeGraph(nodes);

        Map<List<Node>, Set<Node>> S_hat = new HashMap<>();
        Map<List<Node>, Set<Double>> V = new HashMap<>();
        Map<List<Node>, Double> P1 = new HashMap<>();

        int l = -1;

        while (degree(G1) - 1 > l) {
            l = l + 1;

            List<List<Node>> del = new ArrayList<>();

            for (Node x : nodes) {
                List<Node> adjx = G1.getAdjacentNodes(x);

                for (Node y : adjx) {
                    List<Node> _adjx = new ArrayList<>(adjx);
                    _adjx.remove(y);

                    if (_adjx.size() < l) continue;

                    ChoiceGenerator gen = new ChoiceGenerator(_adjx.size(), l);
                    int[] choice;

                    while ((choice = gen.next()) != null) {
                        List<Node> S = GraphUtils.asList(choice, _adjx);

                        double p = pvalue(x, y, S);
                        if (Double.isNaN(p)) continue;

                        if (p > alpha) {
                            del.add(list(x, y));
                            includeSet(S_hat, x, y, S);
                            includeSet(S_hat, y, x, S);
                            clear(V, x, y);
                            clear(V, y, x);
                            break;
                        } else {
                            addP(V, x, y, p);
                            addP(V, y, x, p);
                        }
                    }
                }
            }

            for (List<Node> list : del) {
                Node x = list.get(0);
                Node y = list.get(1);

                G1.removeEdge(x, y);
            }
        }

        for (List<Node> pair : V.keySet()) {
            if (!V.get(pair).isEmpty()) {
                setP(P1, pair, max(V.get(pair)));
            } else {
                P1.remove(pair);
            }
        }

        for (List<Node> triple : getUT(G1)) {
            Node x = triple.get(0);
            Node y = triple.get(1);
            Node z = triple.get(2);

            if (G1.isAdjacentTo(x, z)) continue;

            double pMax = Double.NEGATIVE_INFINITY;
            List<Node> sMax = null;

            for (List<Node> S : getC(x, z, G1)) {
                double _p = pvalue(x, z, S);
                if (Double.isNaN(_p)) continue;

                if (_p > pMax) {
                    pMax = _p;
                    sMax = S;
                }
            }

            if (pMax > Double.NEGATIVE_INFINITY) {
                addP(V, x, z, pMax);
                addP(V, z, x, pMax);
                includeSet(S_hat, x, z, sMax);
            }
        }

        // algorithm 2

        Set<List<Node>> R0 = new HashSet<>();
        Set<Double> T = new HashSet<>();
        Map<List<Node>, Set<Double>> Tp = new HashMap<>();
        Map<List<Node>, Double> P2 = new HashMap<>();
        Set<List<Node>> amb = new HashSet<>();
        Set<List<Node>> ut = getUT(G1);

        for (List<Node> triple : ut) {
            Node x = triple.get(0);
            Node y = triple.get(1);
            Node z = triple.get(2);

            if (G1.isAdjacentTo(x, z)) continue;

            if (!S_hat.get(list(x, z)).contains(y)) {
                G1.setEndpoint(x, y, Endpoint.ARROW);
                G1.setEndpoint(z, y, Endpoint.ARROW);

                addRecord(R0, x, y, z);
                addRecord(R0, z, y, x);

                List<List<Node>> c = getC(x, z, y, G1);

                for (List<Node> cond : c) {
                    double p = pvalue(x, z, cond);
                    if (Double.isNaN(p)) continue;
                    T.add(p);
                }

                addP(Tp, z, y, max(P1.get(list(x, y)), max(T)));
                addP(Tp, x, y, max(P1.get(list(z, y)), max(T)));
            }
        }

        // unorientation procedure for A2
        Graph G2 = new EdgeListGraph(G1);

        for (Edge edge : G1.getEdges()) {
            if (!Edges.isBidirectedEdge(edge)) continue;

            Node x = edge.getNode1();
            Node y = edge.getNode2();

            List<Node> intox = G1.getNodesInTo(x, Endpoint.ARROW);
            for (Node w : intox) {
                G2.removeEdge(x, w);
                G2.addUndirectedEdge(x, w);
                addRecord(amb, w, x);
                addRecord(amb, x, w);
            }

            List<Node> intoy = G1.getNodesInTo(y, Endpoint.ARROW);
            for (Node w : intoy) {
                G2.removeEdge(y, w);
                G2.addUndirectedEdge(y, w);
                addRecord(amb, w, y);
                addRecord(amb, y, w);
            }

            for (Node _x : nodes) {
                for (Node _y : nodes) {
                    if (x == y) continue;

                    if (G1.containsEdge(Edges.directedEdge(_x, _y))) {
                        setP(P2, list(_x, _y), sum(Tp.get(list(_x, _y))));
                    }
                }
            }
        }


        if (true) return G2;


        // algorithm 3
        Set<List<Node>> R1 = new HashSet<>();
        Set<List<Node>> R2 = new HashSet<>();
        Set<List<Node>> R3 = new HashSet<>();

        Set<List<Node>> tri = getTri(G1);
        Set<List<Node>> kite = getKite(G1);

        boolean loop = true;

        while (loop) {
            loop = false;

            for (List<Node> triple : ut) {
                Node x = triple.get(0);
                Node y = triple.get(1);
                Node z = triple.get(2);

                if (G2.containsEdge(Edges.directedEdge(x, y))
                        && !(existsRecord(amb, y, z) && Edges.isBidirectedEdge(G1.getEdge(y, z)))
                        && !existsRecord(union(R0, R1), x, y, z)) {
                    G2.setEndpoint(y, z, Endpoint.ARROW);
                    addRecord(R1, x, y, z);

                    loop = true;
                }
            }

            for (List<Node> record : tri) {
                Node y = record.get(0);
                Node z = record.get(1);
                Node x = record.get(2);

                if (G2.containsEdge(Edges.directedEdge(y, x))
                        && G2.containsEdge(Edges.directedEdge(x, z))
                        && !(existsRecord(amb, y, z) && Edges.isBidirectedEdge(G1.getEdge(y, z)))
                        && !existsRecord(R2, y, x, z)) {
                    G2.setEndpoint(y, z, Endpoint.ARROW);
                    addRecord(R2, y, x, z);
                    loop = true;
                }
            }

            for (List<Node> record : kite) {
                Node y = record.get(0);
                Node x = record.get(1);
                Node w = record.get(2);
                Node z = record.get(3);

                if (G2.containsEdge(Edges.undirectedEdge(y, x))
                        && G2.containsEdge(Edges.undirectedEdge(y, w))
                        && G2.containsEdge(Edges.directedEdge(x, z))
                        && G2.containsEdge(Edges.directedEdge(w, z))
                        && !(existsRecord(amb, y, z) && Edges.isBidirectedEdge(G1.getEdge(y, z)))
                        && !existsRecord(R3, y, x, w, z)
                        && !existsRecord(R3, y, w, x, z)
                ) {
                    G2.setEndpoint(y, z, Endpoint.ARROW);
                    addRecord(R3, y, x, w, z);
                    addRecord(R3, y, w, x, z);
                    loop = true;
                }
            }
        }

        for (List<Node> record : R3) {
            Node y = record.get(0);
            Node x = record.get(1);
            Node w = record.get(2);
            Node z = record.get(3);

            if (existsRecord(R2, y, x, z) || existsRecord(R2, y, w, z)) {
                R3.remove(record);
            }
        }

        // defining evidence of orientation

        Map<List<Node>, Set<List<Node>>> e0 = new HashMap<>();
        Map<List<Node>, Set<List<Node>>> e1 = new HashMap<>();
        Map<List<Node>, Set<List<Node>>> e2 = new HashMap<>();
        Map<List<Node>, Set<List<Node>>> e3 = new HashMap<>();

        for (List<Node> record : R0) {
            Node x = record.get(0);
            Node y = record.get(1);
            Node z = record.get(2);

            addList(e0, y, z, list(x, z));
        }

        for (List<Node> record : R1) {
            Node x = record.get(0);
            Node y = record.get(1);
            Node z = record.get(2);

            addList(e1, y, z, list(x, y));
        }

        for (List<Node> record : R2) {
            Node y = record.get(0);
            Node x = record.get(1);
            Node z = record.get(2);

            addList(e2, y, z, list(y, x));
            addList(e2, y, z, list(x, z));
        }

        for (List<Node> record : R3) {
            Node y = record.get(0);
            Node x = record.get(1);
            Node w = record.get(2);
            Node z = record.get(3);

            addList(e3, y, z, list(y, x));
            addList(e3, y, z, list(y, w));
            addList(e3, y, z, list(x, z));
            addList(e3, y, z, list(w, z));
        }

        Map<List<Node>, Set<List<Node>>> eAll = union(e0, e1, e2, e3);
        Map<List<Node>, Set<List<Node>>> e123 = union(e1, e2, e3);

        Graph G3 = new EdgeListGraph(G2);

        for (Edge edge : G2.getEdges()) {
            if (!Edges.isBidirectedEdge(edge)) {
                continue;
            }

            Node y = edge.getNode1();
            Node z = edge.getNode2();

            G3.removeEdge(y, z);
            G3.addUndirectedEdge(y, z);

            addRecord(amb, y, z);
            addRecord(amb, z, y);

            for (List<Node> record : union(eAll.get(list(y, z)),
                    eAll.get(list(z, y)))) {
                Node x = record.get(0);
                Node w = record.get(1);

                G3.removeEdge(x, w);
                G3.addUndirectedEdge(x, w);

                addRecord(amb, x, w);
                addRecord(amb, w, x);
            }
        }

        // algorithm 4

        Set<List<Node>> directed = new HashSet<>();
        Set<List<Node>> undirected = new HashSet<>();

        for (Node y : nodes) {
            for (Node z : nodes) {
                if (y == z) continue;

                if (G3.containsEdge(Edges.directedEdge(y, z))) directed.add(list(y, z));
                if (G3.containsEdge(Edges.undirectedEdge(y, z))) undirected.add(list(y, z));
            }
        }

        Map<List<Node>, Double> P3 = Collections.synchronizedMap(new HashMap<>());

        for (List<Node> pairyz : undirected) {
            Node y = pairyz.get(0);
            Node z = pairyz.get(1);

            if (!existsRecord(amb, y, z)) {
                P3.put(pairyz, P1.get(pairyz));
            }
        }

        Set<List<Node>> dup = (new HashSet<>());

        for (List<Node> pairyz : directed) {
            Node y = pairyz.get(0);
            Node z = pairyz.get(1);

            if (e0.containsKey(pairyz) && !e123.containsKey(pairyz)) {
                P3.put(pairyz, max(P1.get(pairyz), P2.get(pairyz)));

                if (e0.get(pairyz).size() == 1 && !dup.contains(pairyz)) {
                    Node x = findR1X(R0, z, y);

                    List<Node> pairxz = list(x, z);

                    if (e0.get(pairxz).size() == 1) {
                        dup.add(pairxz);
                    }
                }
            }
        }

        for (List<Node> pairyz : directed) {
            if (e0.get(pairyz) == null) {
                P2.put(pairyz, 0.0);
            }
        }

        // Avoids R1 cycles.
        Set<List<Node>> trail = new HashSet<>();

        // ...recursive
        for (List<Node> pairyz : directed) {
            P3.put(pairyz, getP3(pairyz, P1, P2, P3, R1, R2, R3, trail));
        }

        for (List<Node> pairyz : undirected) {
            Node y = pairyz.get(0);
            Node z = pairyz.get(1);

            if (!dup.contains(pairyz)) {
                List<Node> pairzy = list(z, y);
                dup.add(pairzy);
            }
        }

        // algorithm 5

        List<List<Node>> Pp = new ArrayList<>(P3.keySet());
        Pp.removeAll(dup);
        int m = Pp.size();

        if (m > 0) {

            Pp.sort(Comparator.comparingDouble(P3::get));

            double sum = 0;

            for (int i = 1; i <= m; i++) {
                sum += 1. / i;
            }

            int R = 1;

            for (int i = 1; i <= m; i++) {
                if (P3.get(Pp.get(i - 1)) < alpha) {
                    R = i;
                }
            }

            // pr here is the alpha value for tests that yields the minimum FDR.
            double fdr = m * alpha * sum / max(R, 1);

            this.fdr = fdr;

            double[] q = new double[m + 1];

            for (int k = 1; k <= m; k++) {
                double pk = P3.get(Pp.get(k - 1));
                q[k] = (m * pk * sum) / max(k, 1);
            }

            double max = 0;
            int j = 1;

            for (int k = 1; k <= m; k++) {
                if (q[k] >= max && q[k] <= getQ()) {
                    max = q[k];
                    j = k;
                }
            }

            double alphaStar = P3.get(Pp.get(j - 1));

            this.alphaStar = alphaStar;

            Graph GStar = new EdgeListGraph(G3);

            System.out.println("\nEdges removed by FDR:\n");

            Set<Edge> fdrRemove = new TreeSet<>();

            for (int s = R + 1; s <= Pp.size(); s++) {
                List<Node> list = Pp.get(s - 1);

                Node x = list.get(0);
                Node y = list.get(1);

                fdrRemove.add(GStar.getEdge(x, y));
            }

            for (Edge edge : fdrRemove) {
                System.out.println(edge);
                GStar.removeEdge(edge);
            }

            if (fdrRemove.isEmpty()) System.out.println("--NONE--");

            System.out.println("\nAmbiguous edges:");

            Set<Edge> _amb = new TreeSet<>();

            for (List<Node> list : amb) {
                Node x = list.get(0);
                Node y = list.get(1);

                if (GStar.isAdjacentTo(x, y)) {
                    _amb.add(GStar.getEdge(x, y));
                }
            }

            for (Edge edge : _amb) {
                System.out.println(edge);
                edge.setLineColor(Color.RED);
            }

            if (_amb.isEmpty()) System.out.println("\n--NONE--");

            System.out.println("\nP-values for non-ambiguous edges");

            for (List<Node> list : Pp) {
                Node x = list.get(0);
                Node y = list.get(1);

                System.out.println(GStar.getEdge(x, y) + " p = " + P3.get(list));
            }

            if (Pp.isEmpty()) System.out.println("\n--NONE--");

            System.out.println("\nFDR = " + fdr);
            System.out.println("alphaStar = " + alphaStar);
            System.out.println();

            return GStar;
        } else {
            System.out.println("Not doing FDR; there are no edges with p-values.");
        }

        this.elapsed = start - System.currentTimeMillis();

        return G3;
    }


    //============================PUBLIC============================//

    /**
     * @return the independence test being used in the search.
     */
    public IndependenceTest getIndependenceTest() {
        return independenceTest;
    }

    public double getQ() {
        return q;
    }

    public void setQ(double q) {
        if (!(q >= 0 && q <= 1)) throw new IllegalStateException("Q should be in [0, 1].");
        this.q = q;
    }

    public double getFdr() {
        return fdr;
    }

    public double getAlphaStar() {
        return alphaStar;
    }

    @Override
    public long getElapsedTime() {
        return elapsed;
    }

    //============================PRIVATE============================//\

    private double getP3(List<Node> pairyz,
                         Map<List<Node>, Double> P1,
                         Map<List<Node>, Double> P2,
                         Map<List<Node>, Double> P3,
                         Set<List<Node>> R1,
                         Set<List<Node>> R2,
                         Set<List<Node>> R3,
                         Set<List<Node>> trail) {
        if (P3.containsKey(pairyz)) return P3.get(pairyz);
        trail.add(pairyz);

        Node y = pairyz.get(0);
        Node z = pairyz.get(1);

        Set<Double> U = (new HashSet<>());

        for (List<Node> R : R1) {
            Node _x = R.get(0);
            Node _y = R.get(1);
            Node _z = R.get(2);

            if (!(y == _y && z == _z)) continue;

            List<Node> pairxy = list(_x, _y);
            if (trail.contains(pairxy)) continue;

            U.add(getP3(pairxy, P1, P2, P3, R1, R2, R3, trail));
        }

        for (List<Node> R : R2) {
            Node _y = R.get(0);
            Node _x = R.get(1);
            Node _z = R.get(2);

            if (!(y == _y && z == _z)) continue;

            List<Node> pairyx = list(_y, _x);
            List<Node> pairxz = list(_x, _z);

            U.add(max(
                    getP3(pairyx, P1, P2, P3, R1, R2, R3, trail),
                    getP3(pairxz, P1, P2, P3, R1, R2, R3, trail)
                    )
            );
        }

        for (List<Node> R : R3) {
            Node _y = R.get(0);
            Node _x = R.get(1);
            Node _w = R.get(2);
            Node _z = R.get(3);

            if (!(y == _y && z == _z)) continue;

            List<Node> pairyx = list(_y, _x);
            List<Node> pairyw = list(_y, _w);
            List<Node> pairxz = list(_x, _z);
            List<Node> pairwz = list(_w, _z);

            U.add(max(
                    getP3(pairyx, P1, P2, P3, R1, R2, R3, trail),
                    getP3(pairyw, P1, P2, P3, R1, R2, R3, trail),
                    getP3(pairxz, P1, P2, P3, R1, R2, R3, trail),
                    getP3(pairwz, P1, P2, P3, R1, R2, R3, trail)
            ));
        }

        double max = 0.0;

        if (P1.containsKey(pairyz) && P1.get(pairyz) > max) max = P1.get(pairyz);
        if (P2.containsKey(pairyz) && P2.get(pairyz) > max) max = P2.get(pairyz);

        P3.put(pairyz, max(sum(U), max));
        trail.remove(pairyz);
        return P3.get(pairyz);
    }

    private Node findR1X(Set<List<Node>> r0, Node y, Node z) {
        for (List<Node> R : r0) {
            Node _x = R.get(0);
            Node _y = R.get(1);
            Node _z = R.get(2);

            if (y == _y && z == _z) {
                return _x;
            }
        }

        throw new IllegalStateException();
    }

    private List<Node> list(Node... x) {
        List<Node> l = new ArrayList<>();
        addAll(l, x);
        return l;
    }

    private List<List<Node>> getC(Node x, Node y, Node z, Graph G) {
        List<List<Node>> c = new ArrayList<>();

        List<Node> adjx = G.getAdjacentNodes(x);

        DepthChoiceGenerator genx = new DepthChoiceGenerator(adjx.size(), adjx.size());
        int[] choicex;

        while ((choicex = genx.next()) != null) {
            List<Node> cond = GraphUtils.asList(choicex, adjx);
            if (cond.contains(z)) {
                c.add(cond);
            }
        }

        List<Node> adjy = G.getAdjacentNodes(y);

        DepthChoiceGenerator geny = new DepthChoiceGenerator(adjy.size(), adjy.size());
        int[] choicey;

        while ((choicey = geny.next()) != null) {
            List<Node> cond = GraphUtils.asList(choicey, adjy);
            if (cond.contains(z)) {
                c.add(cond);
            }
        }

        return c;
    }

    private List<List<Node>> getC(Node x, Node y, Graph G) {
        List<List<Node>> c = new ArrayList<>();

        List<Node> adjx = G.getAdjacentNodes(x);
        adjx.remove(y);

        DepthChoiceGenerator genx = new DepthChoiceGenerator(adjx.size(), 3);
        int[] choicex;

        while ((choicex = genx.next()) != null) {
            List<Node> cond = GraphUtils.asList(choicex, adjx);
            c.add(cond);
        }

        List<Node> adjy = G.getAdjacentNodes(y);
        adjy.remove(x);

        DepthChoiceGenerator geny = new DepthChoiceGenerator(adjy.size(), 3);
        int[] choicey;

        while ((choicey = geny.next()) != null) {
            List<Node> cond = GraphUtils.asList(choicey, adjy);
            c.add(cond);
        }

        return c;
    }

    @SafeVarargs
    private final Set<List<Node>> union(Set<List<Node>>... r) {
        Set<List<Node>> union = (new HashSet<>());

        for (Set<List<Node>> _r : r) {
            if (_r == null) continue;

            union.addAll(_r);
        }

        return union;
    }

    @SafeVarargs
    private final Map<List<Node>, Set<List<Node>>> union(Map<List<Node>, Set<List<Node>>>... r) {
        Map<List<Node>, Set<List<Node>>> union = (new HashMap<>());

        for (Map<List<Node>, Set<List<Node>>> _r : r) {
            for (List<Node> pair : _r.keySet()) {
                for (List<Node> image : _r.get(pair)) {
                    Node x = pair.get(0);
                    Node y = pair.get(1);

                    addList(union, x, y, image);
                }
            }
        }

        return union;
    }

    private Set<List<Node>> getUT(Graph g) {
        List<Node> nodes = g.getNodes();
        Set<List<Node>> ut = (new HashSet<>());

        for (Node x : nodes) {
            for (Node y : nodes) {
                for (Node z : nodes) {
                    if (x == y || x == z || y == z) continue;

                    if (g.isAdjacentTo(x, y) && g.isAdjacentTo(y, z) && !g.isAdjacentTo(x, z)) {
                        addRecord(ut, x, y, z);
                    }
                }
            }
        }

        return ut;
    }

    private Set<List<Node>> getTri(Graph g) {
        List<Node> nodes = g.getNodes();
        Set<List<Node>> tri = (new HashSet<>());

        for (Node x : nodes) {
            for (Node y : nodes) {
                for (Node z : nodes) {
                    if (x == y || x == z || y == z) continue;

                    if (g.isAdjacentTo(y, x) && g.isAdjacentTo(x, z) && g.isAdjacentTo(y, z)) {
                        addRecord(tri, y, x, z);
                    }
                }
            }
        }

        return tri;
    }

    private Set<List<Node>> getKite(Graph g) {
        List<Node> nodes = g.getNodes();
        Set<List<Node>> kite = (new HashSet<>());

        for (Node x : nodes) {
            for (Node y : nodes) {
                for (Node z : nodes) {
                    for (Node w : nodes) {
                        if (x == y || x == z || x == w || y == z || y == w || z == w) continue;

                        if (g.isAdjacentTo(y, x)
                                && g.isAdjacentTo(y, w)
                                && g.isAdjacentTo(x, z)
                                && g.isAdjacentTo(w, z)
                                && g.isAdjacentTo(y, z)
                                && !g.isAdjacentTo(x, w)
                        ) {
                            addRecord(kite, y, x, w, z);
                        }
                    }
                }
            }
        }

        return kite;
    }

    private void addRecord(Set<List<Node>> R, Node... x) {
        List<Node> l = new ArrayList<>();
        addAll(l, x);
        R.add(l);
    }

    private boolean existsRecord(Set<List<Node>> R, Node... x) {
        List<Node> l = new ArrayList<>();
        addAll(l, x);
        return R.contains(l);
    }

    private Graph completeGraph(List<Node> nodes) {
        return GraphUtils.completeGraph(new EdgeListGraph(nodes));
    }

    private void clear(Map<List<Node>, Set<Double>> v, Node x, Node y) {
        v.computeIfAbsent(list(x, y), k -> new HashSet<>());
        v.get(list(x, y)).clear();
    }

    private void includeSet(Map<List<Node>, Set<Node>> s, Node x, Node y, List<Node> SS) {
        s.computeIfAbsent(list(x, y), k -> new HashSet<>());
        Set<Node> u = s.get(list(x, y));
        u.addAll(SS);
    }

    private void addList(Map<List<Node>, Set<List<Node>>> s, Node x, Node y, List<Node> SS) {
        s.computeIfAbsent(list(x, y), k -> new HashSet<>());
        s.get(list(x, y)).add(SS);
    }

    private void addP(Map<List<Node>, Set<Double>> v, Node x, Node y, double p) {
        v.computeIfAbsent(list(x, y), k -> new HashSet<>());
        v.get(list(x, y)).add(p);
    }

    private void setP(Map<List<Node>, Double> V, List<Node> pair, double p) {
        V.put(pair, p);
    }

    private double max(Set<Double> p) {
        double max = Double.NEGATIVE_INFINITY;

        for (double d : p) {
            if (d > max) max = d;
        }

        return max;
    }

    private double max(double... p) {
        double max = Double.NEGATIVE_INFINITY;

        for (double _p : p) {
            if (_p > max) max = _p;
        }

        return max;
    }

    private double sum(Set<Double> p) {
        double sum = 0;

        for (double d : p) {
            sum += d;
        }

        return sum;
    }

    private int degree(Graph graph) {
        int max = 0;

        for (Node x : graph.getNodes()) {
            int degree = graph.getDegree(x);

            if (degree > max) {
                max = degree;
            }
        }

        return max;
    }

    private double pvalue(Node x, Node y, List<Node> cond) {
        synchronized (independenceTest) {
            independenceTest.isIndependent(x, y, cond);
            return independenceTest.getPValue();
        }
    }
}





