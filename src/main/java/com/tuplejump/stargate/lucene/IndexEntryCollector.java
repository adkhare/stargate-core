/*
 * Copyright 2014, Tuplejump Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.tuplejump.stargate.lucene;

import com.tuplejump.stargate.lucene.Constants;
import com.tuplejump.stargate.lucene.LuceneUtils;
import com.tuplejump.stargate.lucene.Options;
import com.tuplejump.stargate.lucene.Properties;
import com.tuplejump.stargate.lucene.query.Search;
import com.tuplejump.stargate.lucene.query.function.AggregateFunction;
import com.tuplejump.stargate.lucene.query.function.Function;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.FieldValueHitQueue;
import org.apache.lucene.search.Scorer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * User: satya
 * A custom lucene collector to retrieve index entries.
 * An IndexEntry reads from DocValues to construct the row key, primary key and timestamp info.
 */
public class IndexEntryCollector extends Collector {

    FieldValueHitQueue<IndexEntry> hitQueue;
    FieldComparator<?>[] comparators;
    int docBase;
    int totalHits;
    boolean queueFull;
    IndexEntry bottom;
    Scorer scorer;
    int numHits;
    final int[] reverseMul;
    SortedDocValues pkNames;
    SortedDocValues rowKeys;
    NumericDocValues timeStamps;
    List<String> numericDocValueNamesToFetch;
    List<String> binaryDocValueNamesToFetch;
    Map<String, NumericDocValues> numericDocValuesMap = new HashMap<>();
    Map<String, BinaryDocValues> binaryDocValuesMap = new HashMap<>();
    Options options;


    boolean canByPassRowFetch;

    public boolean canByPassRowFetch() {
        return canByPassRowFetch;
    }

    public int getTotalHits() {
        return totalHits;
    }

    public IndexEntryCollector(Function function, Search search, Options options, int maxResults) throws IOException {
        this.options = options;
        org.apache.lucene.search.SortField[] sortFields = search.usesSorting() ? search.sort(options) : null;
        if (sortFields == null) {
            hitQueue = FieldValueHitQueue.create(new org.apache.lucene.search.SortField[]{org.apache.lucene.search.SortField.FIELD_SCORE}, maxResults);
        } else {
            hitQueue = FieldValueHitQueue.create(sortFields, maxResults);
        }
        comparators = hitQueue.getComparators();
        numHits = maxResults;
        reverseMul = hitQueue.getReverseMul();
        numericDocValueNamesToFetch = new ArrayList<>();
        binaryDocValueNamesToFetch = new ArrayList<>();
        if (function instanceof AggregateFunction) {
            AggregateFunction aggregateFunction = (AggregateFunction) function;
            List<String> groupByFields = aggregateFunction.getGroupByFields();
            List<String> aggregateFields = aggregateFunction.getAggregateFields();
            boolean abort = false;
            FieldType[] groupDocValueTypes = null;
            if (groupByFields != null) {
                groupDocValueTypes = new FieldType[groupByFields.size()];
                for (int i = 0; i < groupByFields.size(); i++) {
                    String field = groupByFields.get(i).toLowerCase();
                    FieldType docValType = getDocValueType(options, field);
                    if (docValType == null) {
                        abort = true;
                        break;
                    }
                    groupDocValueTypes[i] = docValType;
                }
            }
            FieldType[] aggDocValueTypes = new FieldType[aggregateFields.size()];
            if (!abort) {
                for (int i = 0; i < aggregateFields.size(); i++) {
                    String field = aggregateFields.get(i);
                    FieldType docValType = getDocValueType(options, field);
                    if (docValType == null) {
                        abort = true;
                        break;
                    }
                    aggDocValueTypes[i] = docValType;
                }
            }
            canByPassRowFetch = !abort;
            if (canByPassRowFetch) {
                if (groupByFields != null)
                    addToFetch(groupByFields.iterator(), groupDocValueTypes);
                addToFetch(aggregateFields.iterator(), aggDocValueTypes);
            }
        }
    }

    private FieldType getDocValueType(Options options, String field) {
        if (field == null) return null;
        FieldType docValType = options.fieldDocValueTypes.get(field);
        if (docValType == null)
            docValType = options.collectionFieldDocValueTypes.get(Constants.dotSplitter.split(field).iterator().next());
        return docValType;
    }

    private void addToFetch(Iterator<String> groupByFields, FieldType[] groupDocValueTypes) {
        int i = 0;
        while (groupByFields.hasNext()) {
            String field = groupByFields.next();
            FieldType docValType = groupDocValueTypes[i++];
            if (docValType != null) {
                if (docValType.numericType() != null)
                    numericDocValueNamesToFetch.add(field);
                else
                    binaryDocValueNamesToFetch.add(field);
            }
        }
    }

    public List<IndexEntry> docs() {
        List<IndexEntry> indexEntries = new ArrayList<>();
        IndexEntry entry;
        while ((entry = hitQueue.pop()) != null) {
            indexEntries.add(entry);
        }
        return indexEntries;
    }

    @Override
    public void setNextReader(AtomicReaderContext context) throws IOException {
        docBase = context.docBase;
        for (int i = 0; i < comparators.length; i++) {
            hitQueue.setComparator(i, comparators[i].setNextReader(context));
        }
        pkNames = LuceneUtils.getPKDocValues(context.reader());
        rowKeys = LuceneUtils.getRKDocValues(context.reader());
        timeStamps = LuceneUtils.getTSDocValues(context.reader());
        for (String docValName : numericDocValueNamesToFetch) {
            numericDocValuesMap.put(docValName, context.reader().getNumericDocValues(Constants.striped + docValName));
        }
        for (String docValName : binaryDocValueNamesToFetch) {
            binaryDocValuesMap.put(docValName, context.reader().getBinaryDocValues(Constants.striped + docValName));
        }

    }

    @Override
    public boolean acceptsDocsOutOfOrder() {
        return false;
    }

    @Override
    public void setScorer(Scorer scorer) throws IOException {
        // set the scorer on all comparators
        for (int i = 0; i < comparators.length; i++) {
            comparators[i].setScorer(scorer);
        }
        this.scorer = scorer;
    }

    @Override
    public void collect(int doc) throws IOException {
        ++totalHits;
        if (queueFull) {
            // Fastmatch: return if this hit is not competitive
            for (int i = 0; ; i++) {
                final int c = reverseMul[i] * comparators[i].compareBottom(doc);
                if (c < 0) {
                    // Definitely not competitive.
                    return;
                } else if (c > 0) {
                    // Definitely competitive.
                    break;
                } else if (i == comparators.length - 1) {
                    // Here c=0. If we're at the last comparator, this doc is not
                    // competitive, since docs are visited in doc Id order, which means
                    // this doc cannot compete with any other document in the queue.
                    return;
                }
            }

            int slot = bottom.slot;
            // This hit is competitive - replace bottom element in queue & adjustTop
            for (int i = 0; i < comparators.length; i++) {
                comparators[i].copy(slot, doc);
            }

            // Compute score only if it is competitive.
            final float score = scorer.score();
            updateBottom(slot, doc, score);

            for (int i = 0; i < comparators.length; i++) {
                comparators[i].setBottom(bottom.slot);
            }
        } else {
            // Startup transient: queue hasn't gathered numHits yet
            final int slot = totalHits - 1;
            // Copy hit into queue
            for (int i = 0; i < comparators.length; i++) {
                comparators[i].copy(slot, doc);
            }

            // Compute score only if it is competitive.
            final float score = scorer.score();
            add(slot, doc, score);
            if (queueFull) {
                for (int i = 0; i < comparators.length; i++) {
                    comparators[i].setBottom(bottom.slot);
                }
            }
        }
    }

    final void updateBottom(int slot, int doc, float score) throws IOException {
        hitQueue.pop();
        bottom = getIndexEntry(slot, doc, score);
        hitQueue.add(bottom);
    }

    final void add(int slot, int doc, float score) throws IOException {
        IndexEntry entry = getIndexEntry(slot, doc, score);
        bottom = hitQueue.add(entry);
        queueFull = (totalHits == numHits);
    }

    IndexEntry getIndexEntry(int slot, int doc, float score) throws IOException {
        String pkName = LuceneUtils.primaryKeyName(pkNames, doc);
        ByteBuffer rowKey = LuceneUtils.byteBufferDocValue(rowKeys, doc);
        long timeStamp = timeStamps.get(doc);
        Map<String, Number> numericDocValues = new HashMap<>();
        Map<String, String> binaryDocValues = new HashMap<>();
        for (Map.Entry<String, NumericDocValues> entry : numericDocValuesMap.entrySet()) {
            Properties.Type type = AggregateFunction.getLuceneType(options, entry.getKey());
            Number number = LuceneUtils.numericDocValue(entry.getValue(), doc, type);
            numericDocValues.put(entry.getKey(), number);
        }
        for (Map.Entry<String, BinaryDocValues> entry : binaryDocValuesMap.entrySet()) {
            binaryDocValues.put(entry.getKey(), LuceneUtils.stringDocValue(entry.getValue(), doc));
        }
        return new IndexEntry(pkName, rowKey, timeStamp, slot, docBase + doc, score, numericDocValues, binaryDocValues);
    }


    public static class IndexEntry extends FieldValueHitQueue.Entry {
        public final String pkName;
        public final ByteBuffer rowKey;
        public final long timestamp;
        public float score;
        Map<String, Number> numericDocValuesMap;
        Map<String, String> binaryDocValuesMap;


        public IndexEntry(String pkName, ByteBuffer rowKey, long timestamp, int slot, int doc, float score, Map<String, Number> numericDocValuesMap, Map<String, String> binaryDocValuesMap) {
            super(slot, doc, score);
            this.pkName = pkName;
            this.rowKey = rowKey;
            this.timestamp = timestamp;
            this.score = score;
            this.binaryDocValuesMap = binaryDocValuesMap;
            this.numericDocValuesMap = numericDocValuesMap;
        }

        public Number getNumber(String field) {
            return numericDocValuesMap.get(field);
        }

        public String getString(String field) {
            return binaryDocValuesMap.get(field);
        }

        @Override
        public String toString() {
            return super.toString() + "pkName[" + pkName + "]" + "rowKey[" + rowKey + "]" + "timestamp[" + timestamp + "]";
        }
    }
}
