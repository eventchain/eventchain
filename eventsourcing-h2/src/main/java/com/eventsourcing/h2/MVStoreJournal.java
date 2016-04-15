/**
 * Copyright 2016 Eventsourcing team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 */
package com.eventsourcing.h2;

import com.eventsourcing.*;
import com.eventsourcing.events.CommandTerminatedExceptionally;
import com.eventsourcing.hlc.HybridTimestamp;
import com.eventsourcing.layout.Deserializer;
import com.eventsourcing.layout.Layout;
import com.eventsourcing.layout.Serializer;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterators;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.AbstractService;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.db.TransactionStore;
import org.h2.mvstore.db.TransactionStore.TransactionMap;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import javax.management.openmbean.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component(property = {"filename=journal.db", "type=MVStoreJournal", "jmx.objectname=com.eventsourcing:type=journal,name=MVStoreJournal"})
@Slf4j
public class MVStoreJournal extends AbstractService implements Journal, JournalMBean {
    private Repository repository;

    @Getter(AccessLevel.PACKAGE) @Setter(AccessLevel.PACKAGE) // getter and setter for tests
    private MVStore store;
    private TransactionMap<UUID, byte[]> commandPayloads;
    private TransactionMap<UUID, byte[]> commandHashes;
    private TransactionMap<byte[], Boolean> hashCommands;
    private TransactionMap<UUID, byte[]> eventPayloads;
    private TransactionMap<UUID, UUID> eventCommands;
    private TransactionMap<byte[], Boolean> hashEvents;
    private TransactionMap<UUID, byte[]> eventHashes;
    private TransactionMap<byte[], byte[]> commandEvents;

    private MVMap<byte[], byte[]> layouts;
    private TransactionStore transactionStore;
    TransactionStore.Transaction readTx;


    public MVStoreJournal(MVStore store) {
        this();
        this.store = store;
    }

    @SneakyThrows
    public MVStoreJournal() {
        layoutInformationLayout = new Layout<>(LayoutInformation.class);
        layoutInformationSerializer = new Serializer<>(layoutInformationLayout);
        layoutInformationDeserializer = new Deserializer<>(layoutInformationLayout);
    }

    @Activate
    protected void activate(ComponentContext ctx) {
        store = MVStore.open((String) ctx.getProperties().get("filename"));
    }

    @Deactivate
    protected void deactivate(ComponentContext ctx) {
        store.close();
    }

    @Override
    protected void doStart() {
        if (repository == null) {
            notifyFailed(new IllegalStateException("repository == null"));
        }

        if (store == null) {
            notifyFailed(new IllegalStateException("store == null"));
        }

        initializeStore();

        repository.getCommands().forEach(new EntityLayoutExtractor());
        repository.getEvents().forEach(new EntityLayoutExtractor());

        notifyStarted();
    }

    @Override
    public void onCommandsAdded(Set<Class<? extends Command>> commands) {
        commands.forEach(new EntityLayoutExtractor());
        reportUnrecognizedEntities();
    }

    @Override
    public void onEventsAdded(Set<Class<? extends Event>> events) {
        events.forEach(new EntityLayoutExtractor());
        reportUnrecognizedEntities();
    }

    void reportUnrecognizedEntities() {
        getUnrecognizedEntities().forEach(layoutInformation -> {
            String properties = Joiner.on("\n  ").join(layoutInformation.properties().stream().
                    map(propertyInformation ->
                            propertyInformation.name() + ": " + propertyInformation.type()).
                    collect(Collectors.toList()));
            log.warn("Unrecognized entity {} (hash: {})\nProperties:\n  {}\n",
                    layoutInformation.className(), BaseEncoding.base16().encode(layoutInformation.hash()), properties);
        });
    }

    public List<LayoutInformation> getUnrecognizedEntities() {
        List<LayoutInformation> result = new ArrayList<>();
        layouts.forEach(new BiConsumer<byte[], byte[]>() {
            @Override
            public void accept(byte[] hash, byte[] info) {
                if (!layoutsByHash.containsKey(BaseEncoding.base16().encode(hash))) {
                    result.add(layoutInformationDeserializer.deserialize(ByteBuffer.wrap(info)));
                }
            }
        });
        return result;
    }


    @Override
    public String getName() {
        return store.getFileStore().getFileName();
    }

    @Override @SneakyThrows
    public TabularData getEntities() {
        CompositeType propertyType = new CompositeType("Entity Property", "Entity Property", new String[]{"Name", "Type"}, new String[]{"Name", "Type"},
                new OpenType[]{SimpleType.STRING, SimpleType.STRING});
        TabularType propertyTabular = new TabularType("Property", "Property", propertyType, new String[]{"Name"});
        CompositeType compositeType = new CompositeType("Entity", "Entity", new String[]{"Name", "Hash", "Recognized", "Properties"}, new String[]{"Name", "Hash", "Recognized", "Properties"},
                new OpenType[]{SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN, propertyTabular});
        TabularDataSupport tabular = new TabularDataSupport(new TabularType("Entity", "Journalled entity", compositeType, new String[]{"Name"}));
        layouts.forEach(new BiConsumer<byte[], byte[]>() {
            @Override @SneakyThrows
            public void accept(byte[] hash, byte[] info) {
                LayoutInformation layoutInformation = layoutInformationDeserializer.deserialize(ByteBuffer.wrap(info));
                boolean recognized = layoutsByHash.containsKey(BaseEncoding.base16().encode(hash));
                Map<String, Object> entity = new HashMap<>();
                entity.put("Name", layoutInformation.className());
                entity.put("Hash", BaseEncoding.base16().encode(layoutInformation.hash()));
                entity.put("Recognized", recognized);
                TabularDataSupport propertyTab = new TabularDataSupport(propertyTabular);
                layoutInformation.properties().forEach(new Consumer<PropertyInformation>() {
                    @Override @SneakyThrows
                    public void accept(PropertyInformation propertyInformation) {
                        propertyTab.put(new CompositeDataSupport(propertyType, new String[]{"Name", "Type"},
                                new Object[]{propertyInformation.name(), propertyInformation.type()}));
                    }
                });
                entity.put("Properties", propertyTab);
                tabular.put(new CompositeDataSupport(compositeType, entity));
            }
        });
        return tabular;
    }

    void initializeStore() {
        MVMap<String, Object> info = store.openMap("info");
        info.putIfAbsent("version", 1);
        store.commit();

        transactionStore = new TransactionStore(this.store);
        transactionStore.init();

        readTx = transactionStore.begin();
        commandPayloads = readTx.openMap("commandPayloads");
        commandHashes = readTx.openMap("commandHashes");
        hashCommands = readTx.openMap("hashCommands");
        eventPayloads = readTx.openMap("eventPayloads");
        eventCommands = readTx.openMap("eventCommands");
        eventHashes = readTx.openMap("eventHashes");
        hashEvents = readTx.openMap("hashEvents");
        commandEvents = readTx.openMap("commandEvents");

        layouts = store.openMap("layouts");
    }

    @Override
    protected void doStop() {
        transactionStore.close();
        store.close();
        notifyStopped();
    }

    private Map<String, Layout> layoutsByHash = new HashMap<>();
    private Map<String, Layout> layoutsByClass = new HashMap<>();

    @Override
    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    @Override @SuppressWarnings("unchecked")
    public long journal(Command<?> command, Journal.Listener listener, LockProvider lockProvider) throws Exception {
        return journal(command, listener, lockProvider, null);
    }

    private long journal(Command<?> command, Journal.Listener listener, LockProvider lockProvider, Stream<Event> events) throws Exception {
        TransactionStore.Transaction tx = transactionStore.begin();
        try {
            Layout commandLayout = layoutsByClass.get(command.getClass().getName());

            ByteBuffer buffer = new Serializer<>(commandLayout).serialize(command);

            ByteBuffer hashBuffer = ByteBuffer.allocate(16 + commandLayout.getHash().length);
            hashBuffer.put(commandLayout.getHash());
            hashBuffer.putLong(command.uuid().getMostSignificantBits());
            hashBuffer.putLong(command.uuid().getLeastSignificantBits());

            TransactionMap<UUID, byte[]> txCommandPayloads = tx.openMap("commandPayloads");
            TransactionMap<byte[], Boolean> txHashCommands = tx.openMap("hashCommands");
            TransactionMap<UUID, byte[]> txCommandHashes = tx.openMap("commandHashes");

            Stream<Event> actualEvents = events == null ? command.events(repository, lockProvider) : events;
            long count = actualEvents.peek(new EventConsumer(tx, command, listener)).count();

            txCommandPayloads.put(command.uuid(), buffer.array());
            txHashCommands.put(hashBuffer.array(), true);
            txCommandHashes.put(command.uuid(), commandLayout.getHash());

            listener.onCommit();

            tx.prepare();
            tx.commit();

            return count;
        } catch (Exception e) {
            tx.rollback();
            listener.onAbort(e);

            try {
                journal(command, listener, lockProvider, Stream.of(new CommandTerminatedExceptionally(command.uuid(), e)));
            } catch (Exception e1) {
                throw e1;
            }

            throw e;
        }

    }

    @Override
    @SneakyThrows @SuppressWarnings("unchecked")
    public <T extends Entity> Optional<T> get(UUID uuid) {
        if (commandPayloads.containsKey(uuid)) {
            byte[] payload = commandPayloads.get(uuid);
            String encodedHash = BaseEncoding.base16().encode(commandHashes.get(uuid));
            Layout<Command<?>> layout = layoutsByHash.get(encodedHash);
            Command command = (Command) layout.getLayoutClass().newInstance().uuid(uuid);
            new Deserializer<>(layout).deserialize(command, ByteBuffer.wrap(payload));
            return Optional.of((T) command);
        }
        if (eventPayloads.containsKey(uuid)) {
            byte[] payload = eventPayloads.get(uuid);
            String encodedHash = BaseEncoding.base16().encode(eventHashes.get(uuid));
            Layout<Event> layout = layoutsByHash.get(encodedHash);
            Event event = (Event) layout.getLayoutClass().newInstance().uuid(uuid);
            new Deserializer<>(layout).deserialize(event, ByteBuffer.wrap(payload));
            return Optional.of((T) event);
        }
        return Optional.empty();

    }

    @Override
    public <T extends Command<?>> Iterator<EntityHandle<T>> commandIterator(Class<T> klass) {
        Layout layout = layoutsByClass.get(klass.getName());
        byte[] hash = layout.getHash();
        Iterator<Map.Entry<byte[], Boolean>> iterator = hashCommands.entryIterator(hashCommands.relativeKey(hash, 1));
        return new EntityHandleIterator<>(iterator, bytes -> Bytes.indexOf(bytes, hash) == 0, new EntityFunction<>(hash, layout));
    }

    @Override
    public <T extends Event> Iterator<EntityHandle<T>> eventIterator(Class<T> klass) {
        Layout layout = layoutsByClass.get(klass.getName());
        byte[] hash = layout.getHash();
        Iterator<Map.Entry<byte[], Boolean>> iterator = hashEvents.entryIterator(hashEvents.relativeKey(hash, 1));
        return new EntityHandleIterator<>(iterator, bytes -> Bytes.indexOf(bytes, hash) == 0, new EntityFunction<>(hash, layout));
    }

    @Override
    public void clear() {
        commandPayloads.clear();
        hashCommands.clear();
        eventPayloads.clear();
        eventCommands.clear();
        eventHashes.clear();
        hashEvents.clear();
        commandEvents.clear();
        layouts.clear();
    }

    @Override @SuppressWarnings("unchecked")
    public <T extends Entity> long size(Class<T> klass) {
        if (Event.class.isAssignableFrom(klass)) {
            return Iterators.size(eventIterator((Class<Event>)klass));
        }
        if (Command.class.isAssignableFrom(klass)) {
            return Iterators.size(commandIterator((Class<Command<?>>)klass));
        }
        throw new IllegalArgumentException();
    }

    @Override
    public <T extends Entity> boolean isEmpty(Class<T> klass) {
        if (Event.class.isAssignableFrom(klass)) {
            return !eventIterator((Class<Event>)klass).hasNext();
        }
        if (Command.class.isAssignableFrom(klass)) {
            return !commandIterator((Class<Command<?>>)klass).hasNext();
        }
        throw new IllegalArgumentException();
    }

    @Accessors(fluent = true)
    public static class PropertyInformation {
        @Getter @Setter
        private String name;

        @Getter @Setter
        private String type;
    }

    @Accessors(fluent = true)
    public static class LayoutInformation {
        @Getter @Setter
        private byte[] hash;

        @Getter @Setter
        private String className;
        @Getter @Setter
        private List<PropertyInformation> properties;
    }

    private final Layout<LayoutInformation> layoutInformationLayout;
    private final Serializer<LayoutInformation> layoutInformationSerializer;
    private final Deserializer<LayoutInformation> layoutInformationDeserializer;


    private class EntityLayoutExtractor implements Consumer<Class<? extends Entity>> {

        @Override
        @SneakyThrows
        public void accept(Class<? extends Entity> aClass) {
            Layout<? extends Entity> layout = new Layout<>(aClass);
            byte[] hash = layout.getHash();
            String encodedHash = BaseEncoding.base16().encode(hash);
            layoutsByHash.put(encodedHash, layout);
            layoutsByClass.put(aClass.getName(), layout);

            List<PropertyInformation> properties = layout.getProperties().stream().
                    map(property -> new PropertyInformation().
                            name(property.getName()).
                            type(property.getType().getBriefDescription())).
                    collect(Collectors.toList());

            LayoutInformation layoutInformation = new LayoutInformation().
                    className(aClass.getName()).
                    hash(hash).
                    properties(properties);
            
            layouts.put(hash, layoutInformationSerializer.serialize(layoutInformation).array());
        }

    }

    static private class EntityHandleIterator<K, V, R> implements Iterator<R> {

        private final Iterator<Map.Entry<K, V>> iterator;
        private Function<K, Boolean> hasNext;
        private final BiFunction<K, V, R> function;
        private Map.Entry<K, V> entry;

        public EntityHandleIterator(Iterator<Map.Entry<K, V>> iterator, Function<K, Boolean> hasNext, BiFunction<K, V, R>function) {
            this.iterator = iterator;
            this.hasNext = hasNext;
            this.function = function;
        }

        @Override
        public boolean hasNext() {
            if (iterator.hasNext()) {
                entry = iterator.next();
                return hasNext.apply(entry.getKey());
            } else {
                return false;
            }
        }

        @Override
        public R next() {
            if (entry == null) {
                entry = iterator.next();
            }
            R result = function.apply(entry.getKey(), entry.getValue());
            entry = null;
            return result;
        }
    }

    private class EntityFunction<T extends Entity> implements BiFunction<byte[], Boolean, EntityHandle<T>> {
        private final byte[] hash;
        private final Layout layout;

        public EntityFunction(byte[] hash, Layout layout) {
            this.hash = hash;
            this.layout = layout;
        }

        @Override
        public EntityHandle<T> apply(byte[] bytes, Boolean aBoolean) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            UUID uuid = new UUID(buffer.getLong(hash.length), buffer.getLong(hash.length + 8));
            return new EntityHandle<>(MVStoreJournal.this, uuid);
        }
    }

    private class EventConsumer implements Consumer<Event> {
        private final HybridTimestamp ts;
        private final TransactionStore.Transaction tx;
        private final Command<?> command;
        private final Journal.Listener listener;
        private final TransactionMap<byte[], byte[]> txCommandEvents;
        private final TransactionMap<UUID, UUID> txEventCommands;
        private final TransactionMap<UUID, byte[]> txEventHashes;
        private final TransactionMap<byte[], Boolean> txHashEvents;
        private final TransactionMap<UUID, byte[]> txEventPayloads;

        public EventConsumer(TransactionStore.Transaction tx, Command<?> command, Journal.Listener listener) {
            this.tx = tx;
            this.command = command;
            this.listener = listener;
            this.ts = command.timestamp().clone();
            txEventPayloads = tx.openMap("eventPayloads");
            txHashEvents = tx.openMap("hashEvents");
            txEventHashes = tx.openMap("eventHashes");
            txEventCommands = tx.openMap("eventCommands");
            txCommandEvents = tx.openMap("commandEvents");
        }

        @Override
        @SneakyThrows
        public void accept(Event event) {
            ts.update();
            event.timestamp(ts.clone());

            Layout layout = layoutsByClass.get(event.getClass().getName());

            ByteBuffer buffer1 = new Serializer<>(layout).serialize(event);

            ByteBuffer hashBuffer1 = ByteBuffer.allocate(16 + layout.getHash().length);
            hashBuffer1.put(layout.getHash());
            hashBuffer1.putLong(event.uuid().getMostSignificantBits());
            hashBuffer1.putLong(event.uuid().getLeastSignificantBits());

            ByteBuffer commandEventBuf = ByteBuffer.allocate(16 * 2);
            commandEventBuf.putLong(command.uuid().getMostSignificantBits());
            commandEventBuf.putLong(command.uuid().getLeastSignificantBits());
            commandEventBuf.putLong(event.uuid().getMostSignificantBits());
            commandEventBuf.putLong(event.uuid().getLeastSignificantBits());


            txEventPayloads.put(event.uuid(), buffer1.array());
            txHashEvents.put(hashBuffer1.array(), true);
            txEventHashes.put(event.uuid(), layout.getHash());
            txEventCommands.put(event.uuid(), command.uuid());
            txCommandEvents.put(commandEventBuf.array(), layout.getHash());

            listener.onEvent(event);
        }
    }
}
