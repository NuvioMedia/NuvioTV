package android.util;

import java.util.Map;
import java.util.TreeMap;

/**
 * Plain JVM stand-in for the Android class, which the unit test android.jar stubs out to return
 * default values. Test classes come first on the classpath, so extractor tests get this one.
 * Keys stay sorted, as on Android.
 */
@SuppressWarnings("unchecked")
public class SparseArray<E> implements Cloneable {
  private final TreeMap<Integer, E> map = new TreeMap<>();

  public SparseArray() {}

  public SparseArray(int initialCapacity) {}

  public E get(int key) {
    return map.get(key);
  }

  public E get(int key, E valueIfKeyNotFound) {
    E value = map.get(key);
    return value != null || map.containsKey(key) ? value : valueIfKeyNotFound;
  }

  public void put(int key, E value) {
    map.put(key, value);
  }

  public void append(int key, E value) {
    map.put(key, value);
  }

  public void delete(int key) {
    map.remove(key);
  }

  public void remove(int key) {
    map.remove(key);
  }

  public boolean contains(int key) {
    return map.containsKey(key);
  }

  public int size() {
    return map.size();
  }

  public int keyAt(int index) {
    return entryAt(index).getKey();
  }

  public E valueAt(int index) {
    return entryAt(index).getValue();
  }

  public int indexOfKey(int key) {
    int index = 0;
    for (Integer k : map.keySet()) {
      if (k == key) {
        return index;
      }
      index++;
    }
    return -1;
  }

  public void clear() {
    map.clear();
  }

  private Map.Entry<Integer, E> entryAt(int index) {
    int i = 0;
    for (Map.Entry<Integer, E> entry : map.entrySet()) {
      if (i++ == index) {
        return entry;
      }
    }
    throw new ArrayIndexOutOfBoundsException(index);
  }
}
