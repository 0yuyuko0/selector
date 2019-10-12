package com.yuyuko.selector;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class Channel<T> implements Comparable<Channel<T>> {

    protected static class Node<T> {
        private final Thread thread;

        private volatile T data;

        /**
         * 若有select不为null，则是selected完成的标志
         */
        private volatile AtomicBoolean selected;
        /**
         * 是否完成了工作，与unpark状态被破坏区别开来
         */
        private volatile boolean finished;

        public Node(Thread thread) {
            this.thread = thread;
        }

        public void setSelected(AtomicBoolean selected) {
            this.selected = selected;
        }

        public Thread getThread() {
            return thread;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

        public AtomicBoolean getSelected() {
            return selected;
        }

        void setFinished(boolean finished) {
            this.finished = finished;
        }

        boolean isFinished() {
            return finished;
        }
    }

    /**
     * 队列元素个数
     */
    private AtomicInteger count;

    /**
     * 缓冲区大小
     */
    private final int bufferSize;

    /**
     * 发送idx
     */
    private int sendIdx;

    /**
     * 接收idx
     */
    private int recvIdx;

    private final T[] queue;

    /**
     * chan是否关闭
     */
    private volatile boolean closed;

    /**
     * 等待发送到chan的线程
     */
    private final Queue<Node<T>> writeQueue;

    /**
     * 等待从chan接收的线程
     */
    private final Queue<Node<T>> readQueue;

    private final ReentrantLock lock;

    public Channel() {
        this(0);
    }

    public Channel(int bufferSize) {
        this.bufferSize = bufferSize;
        count = new AtomicInteger(0);
        queue = (T[]) new Object[bufferSize];
        writeQueue = (new LinkedList<>());
        readQueue = new LinkedList<>();
        lock = new ReentrantLock(true);
    }

    public void write(T data) {
        writeInternal(data, false);
    }

    protected boolean writeInternal(T data, boolean select) {
        //加锁
        lock.lock();

        //通道已关闭
        if (closed) {
            lock.unlock();
            throw new ChannelAlreadyClosedException("send to closed channel");
        }

        //有线程等待接收
        //直接发送，绕过缓冲区
        Node<T> dequeue = dequeue(readQueue);
        if (dequeue != null) {
            lock.unlock();
            dequeue.setData(data);
            wakeUpNode(dequeue);
            return true;
        }


        //缓冲区有空间
        if (hasAvailableBufferSpace()) {
            queue[sendIdx++] = data;
            if (sendIdx == bufferSize)
                sendIdx = 0;
            count.incrementAndGet();
            lock.unlock();
            return true;
        }

        //走到这一步，没有发现可以直接发送的节点了，如果是select，则返回false
        if (select) {
            lock.unlock();
            return false;
        }

        Thread current = Thread.currentThread();

        Node<T> node = new Node<>(current);

        node.setData(data);

        writeQueue.add(node);

        lock.unlock();

        //等待接收者取数据
        //此处一定要用while，折腾了一天得出的结论
        while (!node.isFinished()) {
            LockSupport.park(current);
        }
        //通道关闭
        if (Thread.interrupted())
            throw new ChannelAlreadyClosedException("send to closed channel");

        return true;
    }

    public T read() {
        return ((T) readInternal(false)[0]);
    }

    /**
     * 两个元素的数组，第一个元素是返回值，第二个元素是是否select成功
     *
     * @return
     */
    Object[] readInternal(boolean select) {
        lock.lock();

        //通道已关闭，返回null
        if (closed && count.get() == 0) {
            lock.unlock();
            return new Object[]{null, true};
        }

        Node<T> dequeue = dequeue(writeQueue);
        if (dequeue != null) {
            lock.unlock();
            wakeUpNode(dequeue);
            return new Object[]{dequeue.getData(), true};
        }

        //缓冲区有数据可以拿
        if (hasDataInBuffer()) {
            T res = queue[recvIdx];
            queue[recvIdx++] = null;
            if (recvIdx == bufferSize)
                recvIdx = 0;
            count.decrementAndGet();
            lock.unlock();
            return new Object[]{res, true};
        }

        if (select) {
            lock.unlock();
            return new Object[]{null, false};
        }

        Thread current = Thread.currentThread();
        Node<T> node = new Node<>(current);

        readQueue.add(node);

        lock.unlock();

        //等待发送者发数据
        while (!node.isFinished()) {
            LockSupport.park(current);
        }
        //通道关闭，返回null
        if (Thread.interrupted())
            return new Object[]{null, true};

        return new Object[]{node.getData(), true};
    }

    /**
     * 唤醒节点
     *
     * @param node node
     */
    private void wakeUpNode(Node<T> node) {
        Thread thread = node.getThread();
        node.setFinished(true);
        LockSupport.unpark(thread);
    }

    public Node<T> dequeue(Queue<Node<T>> queue) {
        while (!queue.isEmpty()) {
            Node<T> node = queue.remove();
            //有select在等待
            if (node.getSelected() != null && !node.getSelected().compareAndSet(false, true))
                continue;
            return node;
        }
        return null;
    }

    public void close() {
        lock.lock();

        if (closed) {
            lock.unlock();
            throw new ChannelAlreadyClosedException("close of closed channel");
        }

        closed = true;

        //打断所有线程
        interruptOnClose(writeQueue);

        interruptOnClose(readQueue);

        lock.unlock();
    }

    private void interruptOnClose(Queue<Node<T>> queue) {
        while (!queue.isEmpty()) {
            Node<T> dequeue = dequeue(queue);
            if (dequeue != null) {
                dequeue.setFinished(true);
                dequeue.getThread().interrupt();
            }
        }
    }

    Queue<Node<T>> getWriteQueue() {
        return writeQueue;
    }

    Queue<Node<T>> getReadQueue() {
        return readQueue;
    }

    boolean hasWaitingWriter() {
        return !writeQueue.isEmpty();
    }

    boolean hasWaitingReader() {
        return !readQueue.isEmpty();
    }

    boolean hasDataInBuffer() {
        return count.get() > 0;
    }

    boolean hasAvailableBufferSpace() {
        return count.get() < bufferSize;
    }

    /**
     * 给select使用
     */
    void lock() {
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public int compareTo(Channel<T> o) {
        return Integer.compare(this.hashCode(), o.hashCode());
    }
}
