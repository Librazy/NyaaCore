package cat.nyaa.nyaacore.database;

import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class MapProvider implements DatabaseProvider {
    @Override
    public Database get(Plugin plugin, Map<String, Object> configuration) {
         return new MapDB<>();
    }

    public static class MapDB<K, V> implements KeyValueDB<K, V>{
        private Map<K, V> map;

        @Override
        public V get(K key) {
            return map.get(key);
        }

        @Override
        public V get(K key, Function<? super K, ? extends V> mappingFunction) {
            return map.computeIfAbsent(key, mappingFunction);
        }

        @Override
        public void put(K key, V value) {
            map.put(key, value);
        }

        @Override
        public Collection<V> getAll(K key) {
            return Collections.singleton(map.get(key));
        }

        @Override
        public Map<K, V> asMap() {
            return map;
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Database> T connect(){
            map = new HashMap<>();
            return (T) this;
        }

        @Override
        public void close() {
            map = null;
        }
    }
}
