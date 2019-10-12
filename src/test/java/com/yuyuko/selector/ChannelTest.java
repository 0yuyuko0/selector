package com.yuyuko.selector;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

public class ChannelTest {
    @RepeatedTest(10)
    void noBuffer() throws InterruptedException {
        test(0, 100, 50, 0);
    }

    @RepeatedTest(10)
    void noBufferWithInterval() throws InterruptedException {
        test(0, 100, 50, 300);
    }

    void test(int bufferSize, int testCnt, int threadCnt, int interval) throws InterruptedException {
        Channel<Integer> channel = new Channel<>(bufferSize);
        AtomicIntegerArray res = new AtomicIntegerArray(testCnt * threadCnt);

        ExecutorService executorService = Executors.newFixedThreadPool(threadCnt * 2);
        CountDownLatch latch = new CountDownLatch(threadCnt * 2);

        for (int i = 0; i < threadCnt; i++) {
            int finalI = i;
            executorService.execute(() -> {
                for (int j = 0; j < testCnt; j++) {
                    channel.write(finalI * testCnt + j);
                    if (interval > 0) {
                        try {
                            TimeUnit.MICROSECONDS.sleep(interval);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                latch.countDown();
            });
        }
        for (int i = 0; i < threadCnt; i++) {
            executorService.execute(() -> {
                for (int j = 0; j < testCnt; j++) {
                    Integer read = channel.read();
                    if (res.get(read) != 0)
                        throw new RuntimeException();
                    res.set(read, 1);
                    try {
                        TimeUnit.MICROSECONDS.sleep(200);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                latch.countDown();
            });
        }
        executorService.shutdown();
        latch.await();

        assertTrue(() -> {
            for (int i = 0; i < res.length(); i++)
                if (res.get(i) == 0)
                    return false;
            return true;
        });
        executorService.shutdownNow();
    }

    @RepeatedTest(10)
    void buffer() throws InterruptedException {
        test(100, 100, 50, 300);
    }

    @RepeatedTest(10)
    void bufferWithInterval() throws InterruptedException {
        test(100, 100, 50, 300);
    }

    @Test
    void closeOnWaitingSend() throws InterruptedException {
        Channel<Integer> channel = new Channel<>();
        new Thread(() -> assertThrows(ChannelAlreadyClosedException.class, () -> channel.write(1))).start();
        TimeUnit.MILLISECONDS.sleep(100);
        new Thread(channel::close).start();
    }

    @Test
    void closeOnWaitingReceive() throws InterruptedException {
        Channel<Integer> channel = new Channel<>();
        new Thread(() -> assertNull(channel.read())).start();
        TimeUnit.MILLISECONDS.sleep(100);
        new Thread(channel::close).start();
    }

    @Test
    void closeOnClosedChannel() {
        Channel<Integer> channel = new Channel<>();
        channel.close();
        assertThrows(ChannelAlreadyClosedException.class, channel::close);
    }
}
