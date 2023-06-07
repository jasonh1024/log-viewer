package com.logviewer.data2.net;

import com.logviewer.data2.*;
import com.logviewer.data2.net.server.*;
import com.logviewer.data2.net.server.api.RemoteTaskController;
import com.logviewer.filters.RecordPredicate;
import com.logviewer.utils.Destroyer;
import com.logviewer.utils.LvGsonUtils;
import com.logviewer.utils.Pair;
import com.logviewer.web.session.LogDataListener;
import com.logviewer.web.session.LogProcess;
import com.logviewer.web.session.SearchResult;
import com.logviewer.web.session.Status;
import com.logviewer.web.session.tasks.SearchPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class RemoteLog implements LogView {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteLog.class);

    private final String logId;
    private final String hostname;
    private final LogPath path;
    private final Node node;
    private final LogFormat format;

    private final String serializedFormat;

    private final RemoteNodeService remoteNodeService;
    private final RemoteLogChangeListenerService remoteLogChangeListenerService;

    public RemoteLog(@NonNull LogPath path, @NonNull LogFormat format, @NonNull String logId, @NonNull String hostname,
                     @NonNull RemoteNodeService remoteNodeService, RemoteLogChangeListenerService remoteLogChangeListenerService) {
        this.path = path;
        node = path.getNode();
        assert node != null;
        this.remoteNodeService = remoteNodeService;
        this.remoteLogChangeListenerService = remoteLogChangeListenerService;

        this.logId = logId;
        this.format = format;
        this.hostname = hostname;

        this.serializedFormat = LvGsonUtils.GSON.toJson(format, LogFormat.class);
    }

    @Override
    public String getId() {
        return logId;
    }

    @Override
    public LogPath getPath() {
        return path;
    }

    @Override
    public String getHostname() {
        return hostname;
    }

    @Override
    public LogFormat getFormat() {
        return format;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public LogProcess loadRecords(RecordPredicate filter, int recordCount, Position start, Position stop, boolean backward, String hash, long sizeLimit, @NonNull LogDataListener listener) {
        return new RemoteLogProcess<>(new RecordLoaderRemoteTask(path.getFile(), serializedFormat, start, stop, backward, hash,
                LvGsonUtils.GSON.toJson(filter, RecordPredicate.class), recordCount, sizeLimit), (o, error) -> {
            if (error != null) {
                listener.onFinish(new Status(error));
                return;
            }

            if (o instanceof RecordList) {
                listener.onData((RecordList) o);
            } else if (o instanceof Status) {
                listener.onFinish((Status) o);
            } else if (o instanceof Throwable) {
                listener.onFinish(new Status((Throwable) o));
            } else {
                LOG.error("Unexpected message {}", o);
            }
        });
    }

    @Override
    public LogProcess createRecordSearcher(@NonNull Position start, boolean backward, RecordPredicate recordPredicate,
                                           @NonNull String hash, int recordCount, @NonNull SearchPattern searchPattern, @NonNull Consumer<SearchResult> listener) {
        return new RemoteLogProcess<>(new RecordSearcherRemoteTask(path.getFile(), serializedFormat,
                start, backward, hash, LvGsonUtils.GSON.toJson(recordPredicate, RecordPredicate.class),
                recordCount, searchPattern),
                (o, error) -> {
                    if (error != null) {
                        listener.accept(new SearchResult(error));
                        return;
                    }

                    listener.accept(o);
                });
    }

    @Override
    public Destroyer addChangeListener(Consumer<FileAttributes> changeListener) {
        return remoteLogChangeListenerService.addListener(path, changeListener);
    }

    @Override
    public CompletableFuture<Throwable> tryRead() {
        CompletableFuture<Throwable> res = new CompletableFuture<>();

        remoteNodeService.startTask(node, new TryReadTask(path.getFile(), serializedFormat), (aVoid, error) -> res.complete(error));

        return res;
    }

    @Override
    public CompletableFuture<Pair<String, Integer>> loadContent(long offset, int length) {
        CompletableFuture<Pair<String, Integer>> res = new CompletableFuture<>();

        remoteNodeService.startTask(node, new LoadContentTask(path.getFile(), offset, length, format.getCharset()), (data, error) -> {
            if (error != null) {
                res.completeExceptionally(error);
            } else {
                res.complete(data);
            }
        });

        return res;
    }

    @Override
    public String toString() {
        return path.toString();
    }

    private class RemoteLogProcess<E, T extends AbstractDataLoaderTask<E>> implements LogProcess {
        private final RemoteTaskController<T> controller;

        RemoteLogProcess(T task, BiConsumer<E, Throwable> callback) {
            controller = remoteNodeService.createTask(node, task, callback);
        }

        @Override
        public void start() {
            remoteNodeService.startTask(controller);
        }

        @Override
        public void setTimeLimit(long timeLimit) {
            controller.alterTask((Consumer<T> & Serializable) task -> {
                task.setTimeLimit(timeLimit);
            });
        }

        @Override
        public void cancel() {
            controller.cancel();
        }
    }
}
