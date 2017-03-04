package org.fulin.chestnut;

import com.google.common.io.Files;
import net.openhft.chronicle.map.ChronicleMap;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * chestnut
 *
 * @author tangfulin
 * @since 17/3/3
 */
public class ListMapService {

    private Logger logger = LoggerFactory.getLogger(getClass());

    // change to /dev/shm for performance
    private static final String DATA_PATH = System.getProperty("java.io.tmpdir") + "/pcc";

    private Map<Long, long[]> smallListMap;
    private Map<Long, long[]> medianListMap;
    private Map<Long, long[]> largeListMap;

    private Map<Long, Long> countMap;

    private int smallThreshold = 10;
    private int medianThreshold = 100;

    public ListMapService(String listName,
                          long smallEntriesSize, int smallValueLength,
                          long mediaEntriesSize, int medianValueLength,
                          long largeEntriesSize, int largeValueLength) throws IOException {
        this.smallThreshold = smallValueLength;
        this.medianThreshold = medianValueLength;

        Files.createParentDirs(new File(DATA_PATH + "/dir"));
        logger.info("chronicle map put data in dir: {}", DATA_PATH);

        smallListMap = ChronicleMap
                .of(Long.class, long[].class)
                .name("small-" + listName)
                .entries(smallEntriesSize)
                .averageValue(new long[smallValueLength])
                .createPersistedTo(new File(DATA_PATH + "/small-" + listName));

        medianListMap = ChronicleMap
                .of(Long.class, long[].class)
                .name("median-" + listName)
                .entries(mediaEntriesSize)
                .averageValue(new long[medianValueLength])
                .createPersistedTo(new File(DATA_PATH + "/median-" + listName));

        largeListMap = ChronicleMap
                .of(Long.class, long[].class)
                .name("large-" + listName)
                .entries(largeEntriesSize)
                .averageValue(new long[largeValueLength])
                .createPersistedTo(new File(DATA_PATH + "/large-" + listName));

        countMap = ChronicleMap
                .of(Long.class, Long.class)
                .name("count-" + listName)
                .entries(smallEntriesSize + mediaEntriesSize + largeEntriesSize)
                .createPersistedTo(new File(DATA_PATH + "/count-" + listName));
    }

    // only for test to clean old data
    public static void cleanData() throws IOException {
        File f = new File(DATA_PATH);
        FileUtils.deleteDirectory(f);
    }

    // TODO add lock
    // assume all positive numbers
    // TODO add to tail or add to head ? do we need reverse ?
    public boolean add(long key, long value) {
        return addToTail(key, value);
    }

    public boolean addToTail(long key, long value) {
        if (key <= 0 || value <= 0) {
            // or throw exception?
            //return false;
            throw new IllegalArgumentException("key and value must be positive");
        }

        int len = getCount(key);

        long[] v = getList(key, 0);

        if (v == null || len <= 0) {
            v = new long[smallThreshold];
            v[0] = value;
            smallListMap.put(key, v);
        } else if (len < smallThreshold && v[len] <= 0) {
            v[len] = value;
            smallListMap.put(key, v);
        } else if (len > smallThreshold && len < medianThreshold && v[len] <= 0) {
            v[len] = value;
            medianListMap.put(key, v);
        } else if (len > medianThreshold && v[len] <= 0) {
            v[len] = value;
            largeListMap.put(key, v);
        } else if (len == smallThreshold && v[len - 1] > 0) {
            long[] v2 = new long[medianThreshold];
            System.arraycopy(v, 0, v2, 0, smallThreshold);
            v = v2;
            v[smallThreshold] = value;
            medianListMap.put(key, v);
            smallListMap.remove(key);
            logger.info("promote from small to median for key {}", key);
        } else if (len == medianThreshold && v[len - 1] > 0) {
            long[] v2 = new long[medianThreshold * 2];
            System.arraycopy(v, 0, v2, 0, medianThreshold);
            v = v2;
            v[medianThreshold] = value;
            largeListMap.put(key, v);
            medianListMap.remove(key);
            logger.info("promote from median to large for key {}", key);
        } else if (len > medianThreshold && v[v.length - 1] > 0) {
            int p = v.length;
            long[] v2 = new long[p * 2];
            System.arraycopy(v, 0, v2, 0, p);
            v = v2;
            v[p] = value;
            largeListMap.put(key, v);
            logger.info("promote from large to large*2 for key {}, size: {}", key, p * 2);
        } else {
            // wtf?
            // TODO log more info
            logger.error("unknown state for key: {}, value len {}, value: {}",
                    key, len, Arrays.asList(v));
            throw new IllegalStateException("unknown state");
        }

        setCount(key, (long) (len + 1));

        return true;
    }

    // so the list size can not > INT.MAX
    public int getCount(long key) {
        Long v = countMap.get(key);
        if (v == null) {
            return 0;
        }
        return v.intValue();
    }

    private void setCount(long key, long value) {
        countMap.put(key, value);
    }

    // pool performance, need a bloom filter before this
    public boolean contains(long key, long value) {
        long[] v = getList(key, 0);
        if (v == null || v.length <= 0) {
            return false;
        }

        for (long vv : v) {
            if (vv == value) {
                return true;
            }
        }
        return false;
    }

    public long[] getList(long key) {
        return getList(key, Integer.MAX_VALUE);
    }

    /**
     * @param key
     * @param size 0 means no truncation, INT.MAX means truncate to len
     * @return
     */
    public long[] getList(long key, int size) {

        int len = getCount(key);

        long[] v;
        if (len <= 0) {
            v = null;
        } else if (len <= smallThreshold) {
            v = smallListMap.get(key);
        } else if (len <= medianThreshold) {
            v = medianListMap.get(key);
        } else {
            v = largeListMap.get(key);
        }

        if (size <= 0 || v == null) {
            return v;
        }

        int copyLen = len > size ? size : len;

        long[] v2 = new long[copyLen];
        System.arraycopy(v, 0, v2, 0, copyLen);
        return v2;
    }
}