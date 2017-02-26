package org.deeplearning4j.parallelism;

import lombok.NonNull;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;


import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limited Queue implementation, suited for multi-gpu prefetch.
 *
 * Basic idea is simple: DataSets are coming from DataSetIterator, and their device location is unknown.
 * So, for better performance DataSets should be transparently moved to the devices where they will be used later, and this should be done in background.
 *
 * @author raver119@gmail.com
 */
public class MagicQueue implements BlockingQueue<DataSet> {
    public enum Mode {
        THREADED,
        SEQUENTIAL,
    }

    protected final List<LinkedBlockingQueue<DataSet>> backingQueues;
    protected final AtomicInteger nextBucket = new AtomicInteger(0);
    protected final int numberOfBuckets;
    protected final List<QueueHandler> handlers;
    protected int capacity = 10;
    protected Mode mode = Mode.THREADED;
    protected AtomicInteger interleavedCounter = new AtomicInteger(0);



    protected MagicQueue(int numberOfFlows, int capacity) {
        backingQueues = new ArrayList<>();
        this.capacity = capacity;
        handlers = new ArrayList<>();
        if (numberOfFlows > 1) {
            for (int i = 0; i < numberOfFlows; i++) {
                LinkedBlockingQueue<DataSet> queue = new LinkedBlockingQueue<>();
                backingQueues.add(queue);

                QueueHandler handler = new QueueHandler(queue);

                Nd4j.getAffinityManager().attachThreadToDevice(handler, i);

                handler.start();
                handlers.add(handler);
            }
        } else {
            LinkedBlockingQueue<DataSet> queue = new LinkedBlockingQueue<>();
            backingQueues.add(queue);
        }

        numberOfBuckets = numberOfFlows;
    }

    /**
     * This method returns average queue size for all devices
     * @return
     */
    @Override
    public int size() {
        if (numberOfBuckets > 1) {
            long cnt = 0;
            for (int i = 0; i < numberOfBuckets; i++) {
                cnt += backingQueues.get(i).size();
            }

            return (int) Math.floor(cnt / numberOfBuckets);
        } else return backingQueues.get(0).size();
    }

    protected int size(int deviceId) {
        if (deviceId >= backingQueues.size())
            throw new RuntimeException("DeviceID exceeds number of actual backing queues");

        return backingQueues.get(deviceId).size();
    }

    @Override
    public boolean isEmpty() {
        return size() < 1;
    }

    /**
     * This method isn't supported
     * @param o
     * @return
     */
    @Override
    public boolean contains(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int drainTo(Collection<? super DataSet> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int drainTo(Collection<? super DataSet> c, int maxElements) {
        throw new UnsupportedOperationException();
    }

    /**
     * This method isn't supported
     * @return
     */
    @Override
    public Iterator<DataSet> iterator() {
        throw new UnsupportedOperationException();
    }

    /**
     * This method isn't supported
     * @return
     */
    @Override
    public Object[] toArray() {
        throw new UnsupportedOperationException();
    }

    /**
     * This method isn't supported
     * @param a
     * @param <T>
     * @return
     */
    @Override
    public <T> T[] toArray(T[] a) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean add(DataSet dataSet) {
        if (numberOfBuckets > 1) {
            synchronized (this) {
                if (nextBucket.get() >= backingQueues.size())
                    nextBucket.set(0);
            }

            handlers.get(nextBucket.getAndIncrement()).put(dataSet);

            return true;
        } else {
            backingQueues.get(0).add(dataSet);
            return true;
        }
    }

    /**
     * This method isn't supported
     * @param o
     * @return
     */
    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    /**
     * This method isn't supported
     * @param c
     * @return
     */
    @Override
    public boolean containsAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection<? extends DataSet> c) {
        return false;
    }

    /**
     * This method isn't supported
     * @param c
     * @return
     */
    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    /**
     * This method isn't supported
     * @param c
     * @return
     */
    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        for(Queue<DataSet> queue: backingQueues) {
            queue.clear();
        }
    }

    @Override
    public boolean offer(DataSet dataSet) {
        if (numberOfBuckets > 1) {
            int deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
            return backingQueues.get(deviceId).offer(dataSet);
        } else return backingQueues.get(0).offer(dataSet);
    }

    @Override
    public void put(DataSet dataSet) throws InterruptedException {
        if (numberOfBuckets > 1) {
            int deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
            backingQueues.get(deviceId).put(dataSet);
        } else backingQueues.get(0).put(dataSet);
    }

    @Override
    public boolean offer(DataSet dataSet, long timeout, TimeUnit unit) throws InterruptedException {
        if (numberOfBuckets > 1) {
            int deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
            return backingQueues.get(deviceId).offer(dataSet, timeout, unit);
        } else return backingQueues.get(0).offer(dataSet, timeout, unit);
    }

    @Override
    public DataSet take() throws InterruptedException {
        if (numberOfBuckets > 1) {
            int deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
            return backingQueues.get(deviceId).take();
        } else return backingQueues.get(0).take();
    }

    @Override
    public DataSet remove() {
        return null;
    }


    /**
     * This method is supposed to be called from managed thread, attached to specific device.
     * It returns 1 DataSet element from head of the queue, and deletes that element from Queue.
     * If queue is empty,
     *
     * Please note: if there's nothing available in Queue - NULL will be returned
     * @param time time to wait for something appear in queue
     * @param timeUnit TimeUnit for time param
     * @return
     */
    public DataSet poll(long time, TimeUnit timeUnit) throws InterruptedException {
        if (mode == Mode.THREADED) {
            if (numberOfBuckets > 1) {
                int deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
                return backingQueues.get(deviceId).poll(time, timeUnit);
            } else return backingQueues.get(0).poll(time, timeUnit);
        } else {
            DataSet ds = backingQueues.get(interleavedCounter.getAndIncrement()).poll(time, timeUnit);
            if (interleavedCounter.get() >= backingQueues.size())
                interleavedCounter.set(0);
            return ds;
        }
    }

    @Override
    public int remainingCapacity() {
        return 0;
    }

    /**
     * This method is supposed to be called from managed thread, attached to specific device.
     * It returns 1 DataSet element from head of the queue, and deletes that element from Queue
     *
     * Please note: if there's nothing available in Queue - NULL will be returned
     *
     * @return
     */
    @Override
    public DataSet poll() {
        if (mode == Mode.THREADED) {
            if (numberOfBuckets > 1) {
                int deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
                return backingQueues.get(deviceId).poll();
            } else return backingQueues.get(0).poll();
        } else {
            DataSet ds = backingQueues.get(interleavedCounter.getAndIncrement()).poll();
            if (interleavedCounter.get() >= backingQueues.size())
                interleavedCounter.set(0);
            return ds;
        }
    }

    @Override
    public DataSet element() {
        return null;
    }

    @Override
    public DataSet peek() {
        return null;
    }

    public static class Builder {
        private int numberOfBuckets = Nd4j.getAffinityManager().getNumberOfDevices();
        private int capacity = 16;
        private Mode mode = Mode.THREADED;

        public Builder() {

        }

        /**
         *
         * @param number
         * @return
         */
        public Builder setNumberOfBuckets(int number) {
            this.numberOfBuckets = number;

            return this;
        }

        /**
         *
         * @param mode
         * @return
         */
        public Builder setMode(@NonNull Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * This method defines, how
         *
         * @param capacityPerFlow
         * @return
         */
        public Builder setCapacityPerFlow(int capacityPerFlow) {
            if (capacityPerFlow <= 0)
                throw new ND4JIllegalStateException("Capacity per flow value should be positive value");

            this.capacity = capacityPerFlow;
            return this;
        }

        public MagicQueue build() {
            if (numberOfBuckets < 1)
                numberOfBuckets = Nd4j.getAffinityManager().getNumberOfDevices();

            MagicQueue queue = new MagicQueue(numberOfBuckets, capacity);


            return queue;
        }
    }

    private static class QueueHandler extends Thread implements Runnable {
        private final Queue<DataSet> targetQueue;
        private final LinkedBlockingQueue<DataSet> bufferQueue;

        public QueueHandler(Queue<DataSet> queue) {
            this.targetQueue = queue;
            this.bufferQueue = new LinkedBlockingQueue<DataSet>();

            this.setDaemon(true);
        }


        public void put(DataSet dataSet) {
            bufferQueue.add(dataSet);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    DataSet ds = bufferQueue.poll(1, TimeUnit.SECONDS);

                    if (ds != null) {
                        // now we initialize dataset on target device (if applicable)
                        if (ds.getFeaturesMaskArray() != null)
                            Nd4j.getAffinityManager().touch(ds.getFeaturesMaskArray());
                        if (ds.getLabelsMaskArray() != null)
                            Nd4j.getAffinityManager().touch(ds.getLabelsMaskArray());

                        Nd4j.getAffinityManager().touch(ds.getFeatures());
                        Nd4j.getAffinityManager().touch(ds.getLabels());

                        targetQueue.add(ds);
                    }
                } catch (Exception e) {
                    //
                }
            }
        }
    }
}
