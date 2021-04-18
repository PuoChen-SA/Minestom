package net.minestom.server.thread;

import net.minestom.server.entity.Entity;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.SharedInstance;
import net.minestom.server.lock.Acquirable;
import net.minestom.server.utils.chunk.ChunkUtils;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Used to link chunks into multiple groups.
 * Then executed into a thread pool.
 */
public abstract class ThreadProvider {

    private final List<BatchThread> threads;

    private final Map<BatchThread, Set<ChunkEntry>> threadChunkMap = new HashMap<>();
    private final Map<Chunk, ChunkEntry> chunkEntryMap = new HashMap<>();
    // Iterated over to refresh the thread used to update entities & chunks
    private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
    private final Set<Entity> removedEntities = ConcurrentHashMap.newKeySet();

    public ThreadProvider(int threadCount) {
        this.threads = new ArrayList<>(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final BatchThread.BatchRunnable batchRunnable = new BatchThread.BatchRunnable();
            final BatchThread batchThread = new BatchThread(batchRunnable, i);
            this.threads.add(batchThread);

            batchThread.start();
        }
    }

    public synchronized void onInstanceCreate(@NotNull Instance instance) {
        instance.getChunks().forEach(this::addChunk);
    }

    public synchronized void onInstanceDelete(@NotNull Instance instance) {
        instance.getChunks().forEach(this::removeChunk);
    }

    public synchronized void onChunkLoad(Chunk chunk) {
        addChunk(chunk);
    }

    public synchronized void onChunkUnload(Chunk chunk) {
        removeChunk(chunk);
    }

    /**
     * Performs a server tick for all chunks based on their linked thread.
     *
     * @param chunk the chunk
     */
    public abstract long findThread(@NotNull Chunk chunk);

    protected void addChunk(Chunk chunk) {
        final int threadId = (int) (Math.abs(findThread(chunk)) % threads.size());
        BatchThread thread = threads.get(threadId);
        var chunks = threadChunkMap.computeIfAbsent(thread, batchThread -> ConcurrentHashMap.newKeySet());

        ChunkEntry chunkEntry = new ChunkEntry(thread, chunk);
        chunks.add(chunkEntry);

        this.chunkEntryMap.put(chunk, chunkEntry);
        this.chunks.add(chunk);
    }

    protected void removeChunk(Chunk chunk) {
        ChunkEntry chunkEntry = chunkEntryMap.get(chunk);
        if (chunkEntry != null) {
            BatchThread thread = chunkEntry.thread;
            var chunks = threadChunkMap.get(thread);
            if (chunks != null) {
                chunks.remove(chunkEntry);
            }
            chunkEntryMap.remove(chunk);
        }
        this.chunks.remove(chunk);
    }

    /**
     * Prepares the update.
     *
     * @param time the tick time in milliseconds
     */
    public synchronized @NotNull CountDownLatch update(long time) {
        CountDownLatch countDownLatch = new CountDownLatch(threads.size());
        for (BatchThread thread : threads) {
            final var chunkEntries = threadChunkMap.get(thread);
            if (chunkEntries == null || chunkEntries.isEmpty()) {
                // The thread never had any task
                countDownLatch.countDown();
                continue;
            }

            // Execute tick
            thread.getMainRunnable().startTick(countDownLatch, () -> {
                final var entitiesList = chunkEntries.stream().map(chunkEntry -> chunkEntry.entities).collect(Collectors.toList());
                final var entities = entitiesList.stream()
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList());
                Acquirable.refreshEntities(Collections.unmodifiableList(entities));
                chunkEntries.forEach(chunkEntry -> {
                    Chunk chunk = chunkEntry.chunk;
                    if (!ChunkUtils.isLoaded(chunk))
                        return;
                    chunk.tick(time);
                    chunkEntry.entities.forEach(entity -> {
                        thread.monitor.enter();
                        entity.tick(time);
                        thread.monitor.leave();
                    });
                });
            });
        }
        return countDownLatch;
    }

    public synchronized void refreshThreads() {
        // Clear removed entities
        {
            for (Entity entity : removedEntities) {
                Acquirable<Entity> acquirable = entity.getAcquiredElement();
                ChunkEntry chunkEntry = acquirable.getHandler().getChunkEntry();
                // Remove from list
                if (chunkEntry != null) {
                    chunkEntry.entities.remove(entity);
                }
            }
            this.removedEntities.clear();
        }


        int size = chunks.size();
        int counter = 0;
        // TODO incremental update, maybe a percentage of the tick time?
        while (counter++ < size) {
            Chunk chunk = chunks.pollFirst();
            if (!ChunkUtils.isLoaded(chunk)) {
                removeChunk(chunk);
                return;
            }

            // Update chunk threads
            {
                // TODO
            }

            // Update entities
            {
                Instance instance = chunk.getInstance();
                refreshEntitiesThread(instance, chunk);
                if (instance instanceof InstanceContainer) {
                    for (SharedInstance sharedInstance : ((InstanceContainer) instance).getSharedInstances()) {
                        refreshEntitiesThread(sharedInstance, chunk);
                    }
                }
            }

            // Add back to the deque
            chunks.addLast(chunk);
        }
    }

    public void removeEntity(@NotNull Entity entity) {
        this.removedEntities.add(entity);
    }

    public void shutdown() {
        this.threads.forEach(BatchThread::shutdown);
    }

    public @NotNull List<@NotNull BatchThread> getThreads() {
        return threads;
    }

    private void refreshEntitiesThread(Instance instance, Chunk chunk) {
        var entities = instance.getChunkEntities(chunk);
        for (Entity entity : entities) {
            Acquirable<Entity> acquirable = entity.getAcquiredElement();
            ChunkEntry handlerChunkEntry = acquirable.getHandler().getChunkEntry();
            Chunk batchChunk = handlerChunkEntry != null ? handlerChunkEntry.getChunk() : null;

            Chunk entityChunk = entity.getChunk();
            if (!Objects.equals(batchChunk, entityChunk)) {
                // Entity is possibly not in the correct thread

                // Remove from previous list
                {
                    if (handlerChunkEntry != null) {
                        handlerChunkEntry.entities.remove(entity);
                    }
                }

                // Add to new list
                {
                    ChunkEntry chunkEntry = chunkEntryMap.get(entityChunk);
                    if (chunkEntry != null) {
                        chunkEntry.entities.add(entity);
                        acquirable.getHandler().refreshChunkEntry(chunkEntry);
                    }
                }
            }
        }
    }

    public static class ChunkEntry {
        private final BatchThread thread;
        private final Chunk chunk;
        private final List<Entity> entities = new ArrayList<>();

        private ChunkEntry(BatchThread thread, Chunk chunk) {
            this.thread = thread;
            this.chunk = chunk;
        }

        public @NotNull BatchThread getThread() {
            return thread;
        }

        public @NotNull Chunk getChunk() {
            return chunk;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkEntry that = (ChunkEntry) o;
            return chunk.equals(that.chunk);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunk);
        }
    }

}