package com.yuyuko.selector;

public class SelectionKey<T> {
    public static final int READ = 0;

    public static final int WRITE = 1;

    public static final int FALLBACK = 2;

    private Channel<T> channel;

    private T data;

    private final int type;

    SelectionKey(T data, int type) {
        this.data = data;
        this.type = type;
    }

    public static <T> SelectionKey<T> read() {
        return new SelectionKey<>(null, READ);
    }

    public static <T> SelectionKey<T> write(T data) {
        return new SelectionKey<>(data, WRITE);
    }

    public static SelectionKey fallback() {
        return new SelectionKey<>(null, FALLBACK);
    }

    protected void setChannel(Channel<T> channel) {
        this.channel = channel;
    }

    protected void setData(T data) {
        this.data = data;
    }

    public int type() {
        return type;
    }

    public T data() {
        return data;
    }

    public Channel<T> channel() {
        return channel;
    }

    @Override
    public String toString() {
        return "SelectionKey{" +
                "data=" + data +
                ", type=" + (type == 0 ? "READ" : type == 1 ? "WRITE" : "FALLBACK") +
                '}';
    }
}