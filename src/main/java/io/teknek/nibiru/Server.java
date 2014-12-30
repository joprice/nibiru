package io.teknek.nibiru;

import io.teknek.nibiru.engine.ColumnFamily;
import io.teknek.nibiru.engine.CompactionManager;
import io.teknek.nibiru.engine.Keyspace;
import io.teknek.nibiru.engine.Val;
import io.teknek.nibiru.metadata.ColumnFamilyMetadata;
import io.teknek.nibiru.metadata.KeyspaceMetadata;
import io.teknek.nibiru.metadata.MetaDataStorage;
import io.teknek.nibiru.metadata.XmlStorage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;

public class Server {
  
  /** will remain null until init() */
  private ConcurrentMap<String,Keyspace> keyspaces;
  private final Configuration configuration;
  
  private TombstoneReaper tombstoneReaper;
  private Thread tombstoneRunnable;
  
  private CompactionManager compactionManager;
  private Thread compactionRunnable;
  
  private final MetaDataStorage storage;
  
  public Server(Configuration configuration){
    this.configuration = configuration;
    tombstoneReaper = new TombstoneReaper(this);
    compactionManager = new CompactionManager(this);
    try {
      storage = (MetaDataStorage) Class.forName(configuration.getMetaDataStorageClass()).newInstance();
    } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }
  
  private ConcurrentMap<String,Keyspace> createKeyspaces(){
    ConcurrentMap<String,Keyspace> keyspaces = new ConcurrentHashMap<>();
    Map<String,KeyspaceMetadata> meta = storage.read(configuration); 
    if (!(meta == null)){
      for (Map.Entry<String, KeyspaceMetadata> entry : meta.entrySet()){
        Keyspace k = new Keyspace(configuration);
        k.setKeyspaceMetadata(entry.getValue());
        keyspaces.put(entry.getKey(), k);
        for (Map.Entry<String, ColumnFamilyMetadata> mentry : entry.getValue().getColumnFamilyMetaData().entrySet()){
          ColumnFamily cf = new ColumnFamily(k, mentry.getValue());
          k.getColumnFamilies().put(mentry.getKey(), cf);
          try {
            cf.init();
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
    return keyspaces;
  }
  
  private void persistMetadata(){
    Map<String,KeyspaceMetadata> meta = new HashMap<>();
    for (Map.Entry<String, Keyspace> entry : keyspaces.entrySet()){
      meta.put(entry.getKey(), entry.getValue().getKeyspaceMetadata());
    }
    storage.persist(configuration, meta);
  }
  
  public void init(){
    keyspaces = createKeyspaces();
    tombstoneRunnable = new Thread(tombstoneReaper);
    tombstoneRunnable.start();
    compactionRunnable = new Thread(compactionManager);
    compactionRunnable.start();
  }
  
  public void shutdown(){
    //these tasks should really be delegated to the column family but for now here is sufficient
    compactionManager.setGoOn(false);
    tombstoneReaper.setGoOn(false);
    for (Map.Entry<String, Keyspace> entry : keyspaces.entrySet()){
      for (Map.Entry<String, ColumnFamily> columnFamilyEntry : entry.getValue().getColumnFamilies().entrySet()){
        columnFamilyEntry.getValue().shutdown();
      }
    }
  }
  
  public void createKeyspace(String keyspaceName){
    KeyspaceMetadata kmd = new KeyspaceMetadata(keyspaceName);
    Keyspace keyspace = new Keyspace(configuration);
    keyspace.setKeyspaceMetadata(kmd);
    keyspaces.put(keyspaceName, keyspace);
    persistMetadata();
  }
  
  public void createColumnFamily(String keyspace, String columnFamily){
    keyspaces.get(keyspace).createColumnFamily(columnFamily);
    persistMetadata();
  }
  
  public void put(String keyspace, String columnFamily, String rowkey, String column, String value, long time){
    Keyspace ks = keyspaces.get(keyspace);
    ks.getColumnFamilies().get(columnFamily)
      .put(rowkey, column, value, time, 0L);
  }
  
  public void put(String keyspace, String columnFamily, String rowkey, String column, String value, long time, long ttl){
    Keyspace ks = keyspaces.get(keyspace);
    ks.getColumnFamilies().get(columnFamily).put(rowkey, column, value, time);
  }
  
  public Val get(String keyspace, String columnFamily, String rowkey, String column){
    Keyspace ks = keyspaces.get(keyspace);
    if (ks == null){
      throw new RuntimeException(keyspace + " is not found");
    }
    return ks.getColumnFamilies().get(columnFamily)
            .get(rowkey, column);
  }
  
  public void delete(String keyspace, String columnFamily, String rowkey, String column, long time){
    Keyspace ks = keyspaces.get(keyspace);
    ks.getColumnFamilies().get(columnFamily).delete(rowkey, column, time);
  }

  public ConcurrentNavigableMap<String, Val> slice(String keyspace, String columnFamily, String rowkey, String startColumn, String endColumn){
    Keyspace ks = keyspaces.get(keyspace);
    return ks.getColumnFamilies().get(columnFamily).slice(rowkey, startColumn, endColumn);
  }
  
  public ConcurrentMap<String, Keyspace> getKeyspaces() {
    return keyspaces;
  }

  public TombstoneReaper getTombstoneReaper() {
    return tombstoneReaper;
  }

  public Configuration getConfiguration() {
    return configuration;
  }

  public CompactionManager getCompactionManager() {
    return compactionManager;
  }
  
}
