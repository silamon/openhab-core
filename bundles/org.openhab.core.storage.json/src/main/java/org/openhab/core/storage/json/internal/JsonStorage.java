/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.storage.json.internal;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.config.core.ConfigurationDeserializer;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.json.StorageMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

/**
 * The JsonStorage is concrete implementation of the {@link Storage} interface.
 * It stores the key-value pairs in files. This Storage serializes and
 * deserializes the given values using JSON (generated by {@code Gson}). A
 * deferred write mechanism of WRITE_DELAY milliseconds is used to improve
 * performance. The service keeps backups in a /backup folder, and maintains a
 * maximum of MAX_FILES at any time
 *
 * @author Chris Jackson - Initial contribution
 * @author Stefan Triller - Removed dependency to internal GSon packages
 * @author Simon Kaufmann - Distinguish between inner and outer
 *         de-/serialization, keep json structures in map
 */
@NonNullByDefault
public class JsonStorage<T> implements Storage<T> {

    private final Logger logger = LoggerFactory.getLogger(JsonStorage.class);

    private final int maxBackupFiles;
    private final int writeDelay;
    private final int maxDeferredPeriod;

    static final String CLASS = "class";
    static final String VALUE = "value";
    private static final String BACKUP_EXTENSION = "backup";
    private static final String SEPARATOR = "--";

    private final Timer commitTimer;
    private @Nullable TimerTask commitTimerTask;

    private long deferredSince = 0;

    protected final File file;
    private final @Nullable ClassLoader classLoader;
    protected final Map<String, StorageEntry> map = new ConcurrentHashMap<>();

    private transient Gson internalMapper;
    protected transient Gson entityMapper;

    private boolean dirty;

    protected JsonStorage(File file, @Nullable ClassLoader classLoader, int maxBackupFiles, int writeDelay,
            int maxDeferredPeriod) {
        this(file, classLoader, maxBackupFiles, writeDelay, maxDeferredPeriod, null);
    }

    protected JsonStorage(File file, @Nullable ClassLoader classLoader, int maxBackupFiles, int writeDelay,
            int maxDeferredPeriod, @Nullable List<StorageMigration> migrations) {
        this.file = file;
        this.classLoader = classLoader;
        this.maxBackupFiles = maxBackupFiles;
        this.writeDelay = writeDelay;
        this.maxDeferredPeriod = maxDeferredPeriod;

        this.internalMapper = new GsonBuilder()
                .registerTypeHierarchyAdapter(Map.class, new StorageEntryMapDeserializer()).setPrettyPrinting()
                .create();
        this.entityMapper = new GsonBuilder().registerTypeAdapter(Configuration.class, new ConfigurationDeserializer())
                .setPrettyPrinting().create();

        commitTimer = new Timer();

        // Apply migrations
        if (migrations != null) {
            Map<String, StorageEntry> migrationMap = this.applyMigrations(migrations);
            // If we've read data from a file, then add it to the map
            if (migrationMap != null) {
                map.putAll(migrationMap);
                dirty = true;
                flush();
                map.clear();
            }
        }

        // Read database
        Map<String, StorageEntry> inputMap = this.readDatabase();
        // If we've read data from a file, then add it to the map
        if (inputMap != null) {
            map.putAll(inputMap);
            logger.debug("Opened Json storage file at '{}'.", file.getAbsolutePath());
        }
    }

    private @Nullable Map<String, StorageEntry> applyMigrations(List<StorageMigration> migrations) {
        Map<String, StorageEntry> storageEntries = this.readDatabase();
        if (storageEntries != null) {
            for (Map.Entry<String, StorageEntry> storageEntryInMap : storageEntries.entrySet()) {
                StorageEntry storageEntry = storageEntryInMap.getValue();
                for (StorageMigration md : migrations) {
                    String oldEntityClassName = md.getOldEntityClassName();
                    String newEntityClassName = md.getNewEntityClassName();

                    Class<?> oldEntityType = md.getOldEntityClass();
                    Class<?> newEntityType = md.getNewEntityClass();

                    if (oldEntityClassName == null) {
                        oldEntityClassName = oldEntityType.getTypeName();
                    }

                    if (newEntityClassName == null) {
                        newEntityClassName = newEntityType.getTypeName();
                    }

                    if (storageEntry.getEntityClassName().equals(oldEntityClassName)) {
                        // load required class within the given bundle context

                        Object valueFromDatabase = this.entityMapper.fromJson((JsonElement) storageEntry.getValue(),
                                oldEntityType);
                        if (valueFromDatabase != null) {
                            Object convertedValue = md.migrate(valueFromDatabase);
                            storageEntry = new StorageEntry(newEntityClassName,
                                    entityMapper.toJsonTree(convertedValue));
                            storageEntries.put(storageEntryInMap.getKey(), storageEntry);
                        }
                    }
                }
            }
        }

        return storageEntries;
    }

    private @Nullable Map<String, StorageEntry> readDatabase() {
        Map<String, StorageEntry> inputMap = null;
        if (file.exists()) {
            // Read the file
            inputMap = readDatabase(file);
        }

        // If there was an error reading the file, then try one of the backup files
        if (inputMap == null) {
            if (file.exists()) {
                logger.info("Json storage file at '{}' seems to be corrupt - checking for a backup.",
                        file.getAbsolutePath());
            } else {
                logger.debug("Json storage file at '{}' does not exist - checking for a backup.",
                        file.getAbsolutePath());
            }
            for (int cnt = 1; cnt <= maxBackupFiles; cnt++) {
                File backupFile = getBackupFile(cnt);
                if (backupFile == null) {
                    break;
                }
                inputMap = readDatabase(backupFile);
                if (inputMap != null) {
                    logger.info("Json storage file at '{}' is used (backup {}).", backupFile.getAbsolutePath(), cnt);
                    break;
                }
            }
        }

        return inputMap;
    }

    @Override
    public @Nullable T put(String key, @Nullable T value) {
        if (value == null) {
            return remove(key);
        }

        StorageEntry val = new StorageEntry(value.getClass().getName(), entityMapper.toJsonTree(value));
        StorageEntry previousValue = map.put(key, val);
        deferredCommit();
        if (previousValue == null) {
            return null;
        }
        return deserialize(previousValue);
    }

    @Override
    public @Nullable T remove(String key) {
        StorageEntry removedElement = map.remove(key);
        deferredCommit();
        if (removedElement == null) {
            return null;
        }
        return deserialize(removedElement);
    }

    @Override
    public boolean containsKey(final String key) {
        return map.containsKey(key);
    }

    @Override
    public @Nullable T get(String key) {
        StorageEntry value = map.get(key);
        if (value == null) {
            return null;
        }
        return deserialize(value);
    }

    @Override
    public Collection<String> getKeys() {
        return map.keySet();
    }

    @Override
    public Collection<@Nullable T> getValues() {
        Collection<@Nullable T> values = new ArrayList<>();
        for (String key : getKeys()) {
            values.add(get(key));
        }
        return values;
    }

    /**
     * Deserializes and instantiates an object of type {@code T} out of the given
     * JSON String. A special classloader (other than the one of the JSON bundle) is
     * used in order to load the classes in the context of the calling bundle.
     */
    @SuppressWarnings("unchecked")
    protected @Nullable T deserialize(@Nullable StorageEntry entry) {
        if (entry == null) {
            // nothing to deserialize
            return null;
        }

        try {
            // load required class within the given bundle context
            Class<T> loadedValueType;
            if (classLoader != null) {
                loadedValueType = (Class<T>) classLoader.loadClass(entry.getEntityClassName());
            } else {
                loadedValueType = (Class<T>) Class.forName(entry.getEntityClassName());
            }

            T value = entityMapper.fromJson((JsonElement) entry.getValue(), loadedValueType);
            logger.trace("deserialized value '{}' from Json", value);
            return value;
        } catch (JsonSyntaxException | JsonIOException | ClassNotFoundException e) {
            logger.error("Couldn't deserialize value '{}'. Root cause is: {}", entry, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    protected @Nullable Map<String, StorageEntry> readDatabase(File inputFile) {
        try {
            final Map<String, StorageEntry> inputMap = new ConcurrentHashMap<>();

            FileReader reader = new FileReader(inputFile);
            Map<String, StorageEntry> loadedMap = internalMapper.fromJson(reader, map.getClass());

            if (loadedMap != null && !loadedMap.isEmpty()) {
                inputMap.putAll(loadedMap);
            }

            return inputMap;
        } catch (JsonSyntaxException | JsonIOException | FileNotFoundException e) {
            logger.error("Error reading JsonDB from {}. Cause {}.", inputFile.getPath(), e.getMessage());
            return null;
        }
    }

    private @Nullable File getBackupFile(int age) {
        List<Long> fileTimes = calculateFileTimes();
        if (fileTimes.size() < age) {
            return null;
        }
        return new File(file.getParent() + File.separator + BACKUP_EXTENSION,
                fileTimes.get(fileTimes.size() - age) + SEPARATOR + file.getName());
    }

    private List<Long> calculateFileTimes() {
        File folder = new File(file.getParent() + File.separator + BACKUP_EXTENSION);
        if (!folder.isDirectory()) {
            return Collections.emptyList();
        }
        List<Long> fileTimes = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File value : files) {
                if (value.isFile()) {
                    String[] parts = value.getName().split(SEPARATOR);
                    if (parts.length != 2 || !parts[1].equals(file.getName())) {
                        continue;
                    }
                    long time = Long.parseLong(parts[0]);
                    fileTimes.add(time);
                }
            }
        }
        Collections.sort(fileTimes);
        return fileTimes;
    }

    private void writeDatabaseFile(File dataFile, String data) {
        try (FileOutputStream outputStream = new FileOutputStream(dataFile, false)) {
            outputStream.write(data.getBytes());
            outputStream.flush();
        } catch (Exception e) {
            logger.error("Error writing JsonDB to {}. Cause {}.", dataFile.getPath(), e.getMessage());
        }
    }

    /**
     * Write out any outstanding data.
     * <p>
     * This creates the backup copy at the same time as writing the database file.
     * This avoids having to either rename the file later (which may leave a small
     * window for there to be no file if the system crashes during the write
     * process), or to copy the file when writing the backup copy (which would
     * require a read and write, and is thus slower).
     */
    public synchronized void flush() {
        // Stop any existing timer
        if (commitTimerTask != null) {
            commitTimerTask.cancel();
            commitTimerTask = null;
        }

        if (dirty) {
            String json = internalMapper.toJson(map);

            synchronized (map) {
                // Write the database file
                writeDatabaseFile(file, json);

                // And also write the backup
                writeDatabaseFile(new File(file.getParent() + File.separator + BACKUP_EXTENSION,
                        System.currentTimeMillis() + SEPARATOR + file.getName()), json);

                cleanupBackups();
                deferredSince = 0;
                dirty = false;
            }
        }
    }

    private void cleanupBackups() {
        List<Long> fileTimes = calculateFileTimes();

        // delete the oldest
        if (fileTimes.size() > maxBackupFiles) {
            for (int counter = 0; counter < fileTimes.size() - maxBackupFiles; counter++) {
                File deleter = new File(file.getParent() + File.separator + BACKUP_EXTENSION,
                        fileTimes.get(counter) + SEPARATOR + file.getName());
                deleter.delete();
            }
        }
    }

    private class CommitTimerTask extends TimerTask {
        @Override
        public void run() {
            // Save the database
            flush();
        }
    }

    public synchronized void deferredCommit() {
        dirty = true;

        // Stop any existing timer
        if (commitTimerTask != null) {
            commitTimerTask.cancel();
            commitTimerTask = null;
        }

        // Handle a maximum time for deferring the commit.
        // This stops a pathological loop preventing saving
        if (deferredSince != 0 && deferredSince < System.nanoTime() - (maxDeferredPeriod * 1000L)) {
            flush();
            // as we committed the database now, there is no need to start a new commit
            // timer
            return;
        }

        if (deferredSince == 0) {
            deferredSince = System.nanoTime();
        }

        // Create the timer task
        commitTimerTask = new CommitTimerTask();

        // Start the timer
        commitTimer.schedule(commitTimerTask, writeDelay);
    }
}
