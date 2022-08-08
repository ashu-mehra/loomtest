package org.loom.test.virtualthreads;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.IntStream;

// Run it as:
// $JAVA_HOME/bin/java --enable-preview -cp target/loomtest-1.0-SNAPSHOT.jar org.loom.test.virtualthreads.VirtualThreadsTest <samplefile>
public class VirtualThreadsTest {
    static Random random = new Random();
    static String filename;

    static boolean threadsStarted = false;
    static boolean threadsDone = false;

    public static void main(String args[]) throws InterruptedException {
        filename = args[0];
        int nthreads = 1000;
        int cycles = 1_000_000;
        if (args.length >= 2) {
            nthreads = Integer.parseInt(args[1]);
        }
        if (args.length >= 3) {
            cycles = Integer.parseInt(args[2]);
        }
        final int workCycles = cycles;
        System.out.println("nthreads: " + nthreads);
        System.out.println("cycles: " + workCycles);
        CyclicBarrier startBarrier = new CyclicBarrier(nthreads, () -> { threadsStarted = true; });
        CyclicBarrier endBarrier = new CyclicBarrier(nthreads, () -> { threadsDone = true; });
        List<Thread> threads = IntStream.range(0, nthreads)
                .mapToObj(i -> Thread.ofVirtual().unstarted(new Task(startBarrier, endBarrier, workCycles)))
                .toList();
        for (var thread : threads) {
            thread.start();
        }
        while (!threadsDone) {
            long timeToSleep = random.nextInt(20000) + 10000;
            Thread.currentThread().sleep(timeToSleep);
            System.out.println("Explicit GC");
            System.gc();
        }
        for (var thread : threads) {
            thread.join();
        }
        System.out.println("Done");
    }

    static class DummyOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            return;
        }
    }

    static class DummyTask implements Runnable {
        @Override
        public void run() {
            Thread.currentThread().yield();
            System.out.println("resumed 1");
            Thread.currentThread().yield();
            System.out.println("resumed 2");
            return;
        }
    }

    static class Task implements Runnable {
        private final CyclicBarrier startBarrier;
        private final CyclicBarrier endBarrier;
        private final int cycles;

        public Task(CyclicBarrier startBarrier, CyclicBarrier endBarrier, int cycles) {
            this.startBarrier = startBarrier;
            this.endBarrier = endBarrier;
            this.cycles = cycles;
        }

        private static int computeChecksum(byte[] data) {
            int result = 0;
            for (int i = 0; i < data.length; i++) {
                result += data[i];
            }
            return result;
        }

        static class FileData {
            List<DataWrapper> list;
            int size;
            FileData() {
                list = new ArrayList<>();
            }
            public void add(DataWrapper chunk) {
                list.add(chunk);
                size += chunk.size();
            }
            public int size() {
                return size;
            }
            public List<DataWrapper> getList() {
                return Collections.unmodifiableList(list);
            }
        }

        static class DataWrapper {
            byte[] data;
            public DataWrapper(byte[] data) {
                this.data = data;
            }
            public int size() {
                return data.length;
            }
            public byte[] getData() {
                return data;
            }
        }

        private static FileData readBytes(int count) {
            FileData fileData = new FileData();
            int i = 0;
            try (FileInputStream fis = new FileInputStream(filename)) {
                while (i < count) {
                    byte[] chunk = new byte[Math.min(32, count - i)];
                    int read = fis.read(chunk);
                    fileData.add(new DataWrapper(chunk));
                    i += read;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return fileData;
        }

        private void copyFileData(byte[] result, int offset, FileData fileData) {
            int counter = offset;
            for (DataWrapper data: fileData.getList()) {
                System.arraycopy(data.getData(), 0, result, counter, data.getData().length);
                counter += data.getData().length;
            }
        }

        private void writeChecksum(int checksum) {
            OutputStream os = new DummyOutputStream();
            try {
                os.write(checksum);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void doWork() {
            int count = random.nextInt(10*1024);
            byte[] result = new byte[count];
            int chunkSize = 1*1024; // read in 1 KB chunks
            int read = 0;
            while (read < count) {
                FileData fileData = readBytes(Math.min(chunkSize, count - read));
                copyFileData(result, read, fileData);
                //System.out.println(Thread.currentThread() + " is yielding");
                Thread.yield();
                read += fileData.size();
                //System.out.println(Thread.currentThread() + " has returned");
            }
            int checksum = computeChecksum(result);
            writeChecksum(checksum);
        }

        @Override
        public void run() {
            try {
                startBarrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
            int cycleCount = 0;
            while (cycleCount < cycles) {
                doWork();
                cycleCount += 1;
                if (cycleCount % 100 == 0) {
                    System.out.println(Thread.currentThread() + " completed " + cycleCount + " cycles");
                }
            }
            try {
                endBarrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }
}
