package com.xlythe.view.camera.pano;

import androidx.annotation.WorkerThread;

import java.util.Arrays;

/**
 * The maximum flow, and with it the minimum cut, of a graph with a source and a sink, by Dinic's
 * algorithm: layer the graph by distance from the source, push blocking flow along the layers,
 * repeat. Seams are cut on small grids, a few thousand nodes each, where this is quick.
 *
 * <p>Augmenting paths are walked with an explicit stack, since a path across a grid can be far
 * longer than a thread's stack allows recursion for.</p>
 */
@WorkerThread
final class MaxFlow {
    /** A capacity nothing can exhaust, for a node that must stay on its side. */
    static final float INFINITE = 1e9f;

    /** Below this a capacity is spent. */
    private static final float EPSILON = 1e-6f;

    /** The ordinary nodes, numbered from 0. */
    private final int mNodes;

    /** The source's number. */
    private final int mSource;

    /** The sink's number. */
    private final int mSink;

    /** Each node's first arc, or -1. */
    private final int[] mHead;

    /** Each arc's next sibling, or -1. */
    private int[] mNext;

    /** Each arc's destination. Arcs come in pairs, e and e ^ 1 opposite each other. */
    private int[] mTo;

    /** Each arc's remaining capacity. */
    private float[] mCapacity;

    /** How many arcs there are. */
    private int mArcs;

    /** Each node's distance from the source in the current layering, or -1. */
    private final int[] mLevel;

    /** Each node's next arc to try in the current layering. */
    private final int[] mCursor;

    MaxFlow(int nodes) {
        mNodes = nodes;
        mSource = nodes;
        mSink = nodes + 1;
        mHead = new int[nodes + 2];
        Arrays.fill(mHead, -1);
        int arcs = Math.max(16, nodes * 6);
        mNext = new int[arcs];
        mTo = new int[arcs];
        mCapacity = new float[arcs];
        mLevel = new int[nodes + 2];
        mCursor = new int[nodes + 2];
    }

    /** Joins two nodes, with a capacity each way. */
    void addEdge(int from, int to, float forward, float backward) {
        ensure(mArcs + 2);
        link(from, to, forward);
        link(to, from, backward);
    }

    /** Ties a node to the source and to the sink. */
    void addTerminals(int node, float source, float sink) {
        if (source > 0) {
            addEdge(mSource, node, source, 0);
        }
        if (sink > 0) {
            addEdge(node, mSink, sink, 0);
        }
    }

    private void link(int from, int to, float capacity) {
        mTo[mArcs] = to;
        mCapacity[mArcs] = capacity;
        mNext[mArcs] = mHead[from];
        mHead[from] = mArcs;
        mArcs++;
    }

    private void ensure(int arcs) {
        if (arcs <= mTo.length) {
            return;
        }
        int size = Math.max(arcs, mTo.length * 2);
        mNext = Arrays.copyOf(mNext, size);
        mTo = Arrays.copyOf(mTo, size);
        mCapacity = Arrays.copyOf(mCapacity, size);
    }

    /** Pushes as much as will go from the source to the sink, and says how much that was. */
    float solve() {
        float total = 0;
        int[] arcStack = new int[mNodes + 2];
        while (layer()) {
            System.arraycopy(mHead, 0, mCursor, 0, mHead.length);
            float pushed;
            while ((pushed = augment(arcStack)) > 0) {
                total += pushed;
            }
        }
        return total;
    }

    /** Whether a node ends on the source's side of the minimum cut. Only after {@link #solve}. */
    boolean isSourceSide(int node) {
        return mLevel[node] >= 0;
    }

    /** Layers the graph by distance from the source, and says whether the sink is reached. */
    private boolean layer() {
        Arrays.fill(mLevel, -1);
        int[] queue = new int[mNodes + 2];
        int head = 0;
        int tail = 0;
        queue[tail++] = mSource;
        mLevel[mSource] = 0;
        while (head < tail) {
            int u = queue[head++];
            for (int e = mHead[u]; e >= 0; e = mNext[e]) {
                int v = mTo[e];
                if (mLevel[v] < 0 && mCapacity[e] > EPSILON) {
                    mLevel[v] = mLevel[u] + 1;
                    queue[tail++] = v;
                }
            }
        }
        return mLevel[mSink] >= 0;
    }

    /** Finds one path along the layers and pushes its bottleneck down it. */
    private float augment(int[] arcStack) {
        int depth = 0;
        int u = mSource;
        while (true) {
            if (u == mSink) {
                float bottleneck = Float.MAX_VALUE;
                for (int k = 0; k < depth; k++) {
                    bottleneck = Math.min(bottleneck, mCapacity[arcStack[k]]);
                }
                for (int k = 0; k < depth; k++) {
                    mCapacity[arcStack[k]] -= bottleneck;
                    mCapacity[arcStack[k] ^ 1] += bottleneck;
                }
                return bottleneck;
            }
            boolean advanced = false;
            for (; mCursor[u] >= 0; mCursor[u] = mNext[mCursor[u]]) {
                int e = mCursor[u];
                int v = mTo[e];
                if (mCapacity[e] > EPSILON && mLevel[v] == mLevel[u] + 1) {
                    arcStack[depth++] = e;
                    u = v;
                    advanced = true;
                    break;
                }
            }
            if (!advanced) {
                if (u == mSource) {
                    return 0;
                }
                // A dead end: nothing more goes through u in this layering.
                mLevel[u] = Integer.MIN_VALUE / 2;
                int arc = arcStack[--depth];
                u = mTo[arc ^ 1];
                mCursor[u] = mNext[mCursor[u]];
            }
        }
    }
}
