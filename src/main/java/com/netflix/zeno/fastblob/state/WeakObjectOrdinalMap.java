/*
 *
 *  Copyright 2014 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.zeno.fastblob.state;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * Weak hash lookup map associate object references to already seen ordinals.
 * The fundamental assumption made here is that objects are immutable, so that
 * once the ordinal is assigned to an object, the ordinal stays the same
 * throughout the life of the object.
 *
 * @author timurua
 *
 */
public class WeakObjectOrdinalMap {

    /**
     * Hashmap entry
     */
    public static class Entry extends WeakReference<Object> {
        // identity hashcode
        private int hash;
        // ordinal
        private int ordinal;
        // membership flags
        private int imageMembershipsFlags;
        // linked list pointer
        private Entry next;

        /**
         * Creates new entry.
         */
        Entry(Object key, ReferenceQueue<Object> queue, int hash, int ordinal, int imageMembershipsFlags, Entry next) {
            super(key, queue);
            this.hash  = hash;
            this.ordinal = ordinal;
            this.imageMembershipsFlags = imageMembershipsFlags;
            this.next  = next;
        }

        public int getOrdinal() {
            return ordinal;
        }

        public int getImageMembershipsFlags() {
            return imageMembershipsFlags;
        }

        @Override
        public String toString() {
            Object v = get();
            return v == null ? "null" : v.toString();
        }
    }

    /**
     * The map is divided into segments to increase concurrency
     */
    private class Segment {
        private static final int MINIMUM_CAPACITY = 256;
        private static final int MAXIMUM_CAPACITY = (1<<30);
        private int count = 0;
        private int maxThreshold = 0;
        private int minThreshold = 0;
        private Entry[] entries;

        private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();

        public Segment(){
            resize(MINIMUM_CAPACITY);
        }

        public synchronized void put(Object object, int hashCode, int ordinal, int imageMembershipsFlags) {
            removeGarbageCollectedEntities();
            int index = index(hashCode, entries.length);
            Entry current = entries[index];
            Entry prev = null;
            while (current != null) {
                if (current.hash == hashCode) {
                    Object currentObject = current.get();
                    if( currentObject == null){
                        deleteEntry(index, current, prev);
                        current = current.next;
                        continue;
                    } else if (currentObject == object) {
                        current.imageMembershipsFlags = (current.imageMembershipsFlags | imageMembershipsFlags);
                        return;
                    }
                }
                prev = current;
                current = current.next;
            }
            count++;
            Entry first = entries[index];
            Entry entry = new Entry(object, queue, hashCode, ordinal, imageMembershipsFlags, first);
            entries[index] = entry;
            entry.next = first;
            checkSize();
            return;
        }

        public synchronized Entry get(Object object, int hashCode) {
            removeGarbageCollectedEntities();
            int index = index(hashCode, entries.length);
            Entry current = entries[index];
            Entry prev = null;
            while (current != null) {
                if (current.hash == hashCode) {
                    Object currentObject = current.get();
                    if( currentObject == null){
                        deleteEntry(index, current, prev);
                        current = current.next;
                        continue;
                    } else if (currentObject == object) {
                        return current;
                    }
                }
                prev = current;
                current = current.next;
            }
            return null;
        }

        private void checkSize() {
            if( count >= minThreshold && count <= maxThreshold ){
                return;
            }
            int newCapacity;
            if( count < minThreshold ) {
                newCapacity = Math.max(MINIMUM_CAPACITY, entries.length >> 1);
            } else {
                newCapacity = Math.min(MAXIMUM_CAPACITY, entries.length << 1);
            }

            resize(newCapacity);
        }

        private void resize(int newCapacity) {
            Entry[] newEntries = new Entry[newCapacity];
            if( entries != null){
                for(Entry entry : entries){
                    Entry current = entry;
                    while(current != null){
                        Entry newEntry = current;
                        current = current.next;
                        int index = index(newEntry.hash, newEntries.length);
                        newEntry.next = newEntries[index];
                        newEntries[index] = newEntry;
                    }
                }
            }
            minThreshold = ( newEntries.length == MINIMUM_CAPACITY ) ? 0 : ( newEntries.length * 75 / 200 );
            maxThreshold = ( newEntries.length == MAXIMUM_CAPACITY ) ? Integer.MAX_VALUE : newEntries.length * 75 / 100;
            entries = newEntries;
        }

        private void removeGarbageCollectedEntities() {
            for (Object x; (x = queue.poll()) != null; ) {
                Entry entry = (Entry) x;
                int index = index(entry.hash, entries.length);
                Entry current = entries[index];
                Entry prev = null;
                while (current != null) {
                    if (current == entry) {
                        deleteEntry(index, current, prev);
                        break;
                    }
                    prev = current;
                    current = current.next;
                }
            }
            checkSize();
        }

        private void deleteEntry(int index, Entry current, Entry prev) {
            count--;
            if (prev != null) {
                prev.next = current.next;
            } else {
                entries[index] = current.next;
            }
        }

        private final int index(int hashCode, int capacity) {
            return (hashCode >>> WeakObjectOrdinalMap.this.logOfSegmentNumber) % capacity;
        }

        public synchronized void clear() {
            for (int i = 0; i < entries.length; i++) {
                entries[i] = null;
            }
            resize(MINIMUM_CAPACITY);
        }

        public synchronized int size() {
            removeGarbageCollectedEntities();
            return count;
        }
    }

    private final Segment[] segments;
    private final int mask;
    private final int logOfSegmentNumber;

    public WeakObjectOrdinalMap(int logOfSegmentNumber) {
        if (logOfSegmentNumber < 1 && logOfSegmentNumber > 32) {
            throw new RuntimeException("Invalid power level");
        }
        segments = new Segment[2 << logOfSegmentNumber];
        for(int i=0; i<segments.length; i++){
            segments[i] = new Segment();
        }
        this.mask = (2 << logOfSegmentNumber) - 1;
        this.logOfSegmentNumber = logOfSegmentNumber;
    }

    /**
     * Associating the obj with an ordinal
     *
     * @param obj
     * @param ordinal
     */
    public void put(Object obj, int ordinal, int imageMembershipsFlags) {
        int hashCode = System.identityHashCode(obj);
        int segment = segment(hashCode);
        segments[segment].put(obj, hashCode, ordinal, imageMembershipsFlags);
    }

    public Entry getEntry(Object obj) {
        int hashCode = System.identityHashCode(obj);
        int segment = segment(hashCode);
        return segments[segment].get(obj, hashCode);
    }

    private final int segment(int hashCode) {
        return hashCode & mask;
    }

    public void clear(){
        for (Segment segment : segments) {
            segment.clear();
        }
    }

    public int size() {
        int size = 0;
        for (Segment segment : segments) {
            size += segment.size();
        }
        return size;
    }
}
