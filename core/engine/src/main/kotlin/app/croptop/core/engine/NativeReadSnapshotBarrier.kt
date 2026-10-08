package app.croptop.core.engine

import java.util.concurrent.locks.ReentrantLock

/** Coordinates native snapshot readers with document teardown. */
internal class NativeReadSnapshotBarrier {
    private val lock = ReentrantLock()
    private val readersDrained = lock.newCondition()
    private var activeReaders = 0

    fun beginRead() {
        lock.lock()
        try {
            activeReaders += 1
        } finally {
            lock.unlock()
        }
    }

    fun endRead() {
        lock.lock()
        try {
            check(activeReaders > 0) { "Native read snapshot lease underflow" }
            activeReaders -= 1
            if (activeReaders == 0) readersDrained.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun awaitReaders() {
        lock.lock()
        try {
            while (activeReaders > 0) readersDrained.awaitUninterruptibly()
        } finally {
            lock.unlock()
        }
    }
}
