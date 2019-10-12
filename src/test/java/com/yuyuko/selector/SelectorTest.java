package com.yuyuko.selector;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static com.yuyuko.selector.SelectionKey.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SelectorTest {
    @RepeatedTest(100)
    void selectReadOnCloseChan() throws InterruptedException {
        selectReadOnCloseChan(true);
        selectReadOnCloseChan(false);
    }

    void selectReadOnCloseChan(boolean sleep) throws InterruptedException {
        Channel<Integer> chan = new Channel<>();
        new Thread(chan::close).start();
        if (sleep)
            TimeUnit.MILLISECONDS.sleep(10);
        SelectionKey key = Selector.open()
                .register(chan, read())
                .select();
        assertNull(key.data());
    }

    @RepeatedTest(10)
    void selectWriteOnCloseChan() throws InterruptedException {
        selectWriteOnCloseChan(true);
        selectWriteOnCloseChan(false);
    }

    void selectWriteOnCloseChan(boolean sleep) throws InterruptedException {
        Channel<Integer> chan = new Channel<>();
        new Thread(chan::close).start();
        if (sleep)
            TimeUnit.MILLISECONDS.sleep(10);
        assertThrows(ChannelAlreadyClosedException.class, () -> {
            Selector.open()
                    .register(chan, write(null))
                    .select();
            fail("shit");
        });
    }

    @RepeatedTest(100)
    void testSelectorInSelectorDone() throws InterruptedException {
        Channel<Object> chan1 = new Channel<>();
        Channel<Object> chan2 = new Channel<>();
        Channel<Object> chan3 = new Channel<>();
        int testCnt = 100;
        CountDownLatch latch = new CountDownLatch(2);
        new Timer()
                .scheduleAtFixedRate(new TimerTask() {
                    @Override
                    public void run() {
                        chan1.write(null);
                    }
                }, 0, 1);
        new Thread(() -> {
            for (int i = 0; i < testCnt; ++i) {
                int finalI = i;
                SelectionKey key = Selector.open()
                        .register(chan1, read())
                        .register(chan2, read())
                        .select();
                if (key.channel() == chan1)
                    Selector.open()
                            .register(chan3, write(new Object()))
                            .select();
            }
            latch.countDown();
        }).start();
        new Thread(() -> {
            for (int i = 0; i < testCnt; ++i) {
                Selector.open()
                        .register(chan2, write(new Object()))
                        .register(chan3, read())
                        .select();
            }
            latch.countDown();
        }).start();
        latch.await();
    }

    @RepeatedTest(1000)
    void testSelectorDeadLock() throws BrokenBarrierException, InterruptedException {
        Channel<Object> chan1 = new Channel<>();
        Channel<Object> chan2 = new Channel<>();
        Channel<Object> chan3 = new Channel<>();
        CyclicBarrier barrier = new CyclicBarrier(3);
        new Thread(() -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
            Selector.open()
                    .register(chan1, read())
                    .register(chan2, read())
                    .register(chan3, read())
                    .select();
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
        }
        ).start();
        new Thread(() -> {
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
            Selector.open()
                    .register(chan3, write(3))
                    .register(chan2, write(2))
                    .register(chan1, write(1))
                    .select();
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
            }
        }).start();
        barrier.await();
        barrier.await();
    }

    @RepeatedTest(10)
    void selectWithTwoChan() throws InterruptedException {
        selectWithNChan(2, 50, 50, 2, 0, 300, false);
        selectWithNChan(2, 50, 50, 0, 2, 300, false);
        selectWithNChan(2, 50, 50, 1, 1, 300, false);
        selectWithNChan(2, 50, 50, 2, 0, 300, true);
        selectWithNChan(2, 50, 50, 0, 2, 300, true);
        selectWithNChan(2, 50, 50, 1, 1, 300, true);
    }

    @RepeatedTest(10)
    void selectWithNSendChan() throws InterruptedException {
        selectWithNChan(10, 5, 50, 10, 0, 300, false);
        selectWithNChan(10, 5, 50, 10, 0, 300, true);
    }

    @RepeatedTest(10)
    void selectWithNReceiveChan() throws InterruptedException {
        selectWithNChan(10, 5, 50, 0, 10, 300, false);
        selectWithNChan(10, 5, 50, 0, 10, 300, true);
    }

    @RepeatedTest(10)
    void selectWithNChan() throws InterruptedException {
        selectWithNChan(10, 10, 50, 5, 5, 300, false);
        selectWithNChan(10, 10, 50, 5, 5, 300, true);
    }

    @RepeatedTest(10)
    void selectTest() throws InterruptedException {
        Channel<Object> chan1 = new Channel<>();
        Channel<Object> chan2 = new Channel<>();


        for (int i = 0; i < 10; i++) {
            new Thread(() -> chan1.write(null)).start();
            new Thread(chan2::read).start();
        }

        TimeUnit.MILLISECONDS.sleep(10);

        for (int i = 0; i < 20; i++) {
            Selector.open()
                    .register(chan1, read())
                    .register(chan2, write(null))
                    .select();
        }
    }

    @RepeatedTest(10)
    void selectWithOneChanTest() throws InterruptedException {
        selectWithNChan(1, 50, 50, 1, 0, 300, false);
        selectWithNChan(1, 50, 50, 1, 0, 300, true);
        selectWithNChan(1, 50, 50, 0, 1, 300, false);
        selectWithNChan(1, 50, 50, 0, 1, 300, true);
    }

    void selectWithNChan(int chanCnt, int threadPerChan, int testPerThread, int sendCnt,
                         int receiveCnt, int interval,
                         boolean hasDefault) throws InterruptedException {
        assert chanCnt == sendCnt + receiveCnt;
        int selectCnt = chanCnt * threadPerChan * testPerThread;

        ExecutorService executorService = Executors.newFixedThreadPool(chanCnt * threadPerChan);

        Channel<Integer>[] chans = ((Channel<Integer>[]) new Channel[chanCnt]);
        for (int j = 0; j < chans.length; j++) {
            chans[j] = new Channel<>();
        }

        CountDownLatch latch = new CountDownLatch(selectCnt);

        List<Integer> receiveFromSelector = Collections.synchronizedList(new ArrayList<>());
        List<Integer> receiveFromSenderChan = Collections.synchronizedList(new ArrayList<>());
        List<Integer> receiveFromSelectorDefault = Collections.synchronizedList(new ArrayList<>());

        int tmpSendCnt = sendCnt;
        int tmpReceiveCnt = receiveCnt;

        for (int i = 0; i < chanCnt; i++) {
            int finalI = i;
            Channel<Integer> chan = chans[i];

            final boolean isSend = --tmpSendCnt >= 0 || --tmpReceiveCnt < 0;
            for (int j = 0; j < threadPerChan; j++) {
                executorService.execute(() -> {
                    for (int k = 0; k < testPerThread; k++) {
                        if (isSend)
                            chan.write(finalI * threadPerChan * testPerThread + k);
                        else
                            receiveFromSelector.add(chan.read());
                        latch.countDown();
                        if (interval > 0) {
                            try {
                                TimeUnit.MICROSECONDS.sleep(interval);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                });
            }
        }

        executorService.shutdown();

        for (int i = 0; i < selectCnt; i++) {
            Selector select = Selector.open();
            for (int j = 0; j < sendCnt; j++) {
                select.register(chans[j], read());
            }
            for (int j = 0; j < receiveCnt; j++) {
                select.register(chans[sendCnt + j], write(i));
            }
            if (hasDefault)
                select.fallback(fallback());
            SelectionKey<Integer> key = select.select();
            if (key.type() == READ) {
                if(key.data() == null)
                    System.out.println("shit");
                receiveFromSenderChan.add(key.data());
            }
            else if (key.type() == FALLBACK) {
                receiveFromSelectorDefault.add(i);
                latch.countDown();
            }
        }

        latch.await();

        assertEquals(selectCnt,
                receiveFromSelector.size() + receiveFromSenderChan.size() + receiveFromSelectorDefault.size());
        assertTrue(receiveFromSelector.stream().allMatch(data -> data != null && data < selectCnt));
        if (!receiveFromSenderChan.stream().allMatch(data -> data != null && data < selectCnt))
            fail();
    }

    @Test
    void testRandom() throws InterruptedException, BrokenBarrierException {
        int testCnt = 1000;
        int chanCnt = 5;

        AtomicInteger[] cnts = new AtomicInteger[chanCnt];
        for (int i = 0; i < cnts.length; i++) {
            cnts[i] = new AtomicInteger();
        }


        for (int i = 0; i < testCnt; i++) {
            Channel<Integer>[] chan = ((Channel<Integer>[]) new Channel[chanCnt]);
            for (int j = 0; j < chan.length; j++) {
                chan[j] = new Channel<>();
            }

            CountDownLatch latch = new CountDownLatch(chan.length);

            for (int j = 0; j < chan.length; j++) {
                int finalJ = j;
                new Thread(() -> {
                    latch.countDown();
                    chan[finalJ].read();
                }).start();
            }

            //等待上面的线程就绪
            latch.await();

            Selector select = Selector.open();
            for (int j = 0; j < chan.length; j++) {
                int finalJ = j;
                select.register(chan[j], write(finalJ));
            }
            SelectionKey<Integer> key = select.select();
            cnts[key.data()].incrementAndGet();
        }
        for (int j = 0; j < chanCnt; j++) {
            System.out.println(String.format("chan %d was selected %d times", j, cnts[j].get()));
        }
    }
}
