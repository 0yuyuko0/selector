package com.yuyuko.selector;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

public class Selector {
    private List<SelectionKey<?>> keys = new ArrayList<>();

    private final AtomicBoolean selected = new AtomicBoolean(false);

    private SelectionKey fallback;

    public <T> Selector register(Channel<T> ch, SelectionKey<T> key) {
        if (ch != null) {
            key.setChannel(ch);
            keys.add(key);
        }
        return this;
    }

    public Selector fallback(SelectionKey key) {
        if (fallback != null)
            throw new RuntimeException("Selector must only have one fallback");
        if (key != null)
            fallback = key;
        return this;
    }

    public static Selector open() {
        return new Selector();
    }

    public SelectionKey<?> select() {
        keys = unorder(keys);

        List<Channel> lockOrder =
                Collections.unmodifiableList(
                        getLockOrder(keys.stream().map(SelectionKey::channel)
                                .collect(Collectors.toList())));
        lockAll(lockOrder);

        for (SelectionKey<?> key : keys) {
            Channel<?> channel = key.channel();
            switch (key.type()) {
                case SelectionKey.WRITE:
                    if (channel.hasWaitingReader() || channel.hasAvailableBufferSpace() || channel.isClosed()) {
                        if (!handleWrite(key, lockOrder))
                            continue;
                        return key;
                    }
                    break;
                case SelectionKey.READ:
                    if (channel.hasWaitingWriter() || channel.hasDataInBuffer() || channel.isClosed()) {
                        if (!handleRead(key, lockOrder))
                            continue;
                        return key;
                    }
                    break;
                default:
            }
        }
        if (fallback != null) {
            unlockAll(lockOrder);
            return fallback;
        }

        Thread thread = Thread.currentThread();

        List<Channel.Node<?>> nodes = new ArrayList<>(keys.size());
        //加入到每个channel的队列里，等待被唤醒

        for (SelectionKey<?> key : keys) {
            Channel.Node node = new Channel.Node(thread);
            node.setSelected(selected);
            if (key.type() == SelectionKey.WRITE) {
                node.setData(key.data());
                key.channel().getWriteQueue().add(node);
            } else
                key.channel().getReadQueue().add(node);
            nodes.add(node);
        }

        unlockAll(lockOrder);

        //等待被唤醒
        while (!selected.get()) {
            LockSupport.park(thread);
        }

        //被唤醒了，找到key
        for (int i = 0; i < nodes.size(); i++) {
            Channel.Node<?> node = nodes.get(i);
            if (node.isFinished()) {
                SelectionKey key = keys.get(i);
                if (key.type() == SelectionKey.WRITE && Thread.interrupted())
                    throw new ChannelAlreadyClosedException("one of the channel in select was " +
                            "closed!");
                else if (key.type() == SelectionKey.READ)
                    key.setData(node.getData());
                return key;
            }
        }

        throw new RuntimeException("select wakeup but no key selected, unreachable!!!");
    }

    @SuppressWarnings("unchecked")
    private <T> boolean handleRead(SelectionKey<T> key, List<Channel> lockOrder) {
        Channel<T> chan = key.channel();
        T data;
        Object[] returnVal = chan.readInternal(true);
        data = ((T) returnVal[0]);
        //select失败
        if (!((Boolean) returnVal[1]))
            return false;
        key.setData(data);
        unlockAll(lockOrder);
        return true;
    }

    @SuppressWarnings("unchecked")
    private <T> boolean handleWrite(SelectionKey<T> key, List<Channel> lockOrder) {
        Channel<T> chan = key.channel();
        try {
            if (!chan.writeInternal(key.data(), true)) {
                return false;
            }
        } catch (ChannelAlreadyClosedException ex) {
            unlockAll(lockOrder);
            throw ex;
        }
        unlockAll(lockOrder);
        return true;
    }

    <T> List<T> unorder(List<T> keys) {
        Random random = new Random();
        int n = keys.size();
        int[] pollOrder = new int[n];
        for (int i = 1; i < n; i++) {
            int j = random.nextInt(i + 1);
            pollOrder[i] = pollOrder[j];
            pollOrder[j] = i;
        }

        List<T> unordered = new ArrayList<>();
        for (int order : pollOrder)
            unordered.add(keys.get(order));
        return unordered;
    }

    /**
     * 将所有case的通道加锁
     *
     * @param lockOrder
     */
    private void lockAll(List<Channel> lockOrder) {
        for (Channel chan : lockOrder) {
            chan.lock();
        }
    }

    /**
     * 将之前加锁的全部case解锁，解锁顺序与加锁顺序相反
     */
    private void unlockAll(List<Channel> lockOrder) {
        ListIterator<Channel> iterator = lockOrder.listIterator(lockOrder.size());
        while (iterator.hasPrevious())
            iterator.previous().unlock();
    }

    private List<Channel> getLockOrder(List<Channel> chans) {
        chans.sort(null);
        return chans;
    }
}