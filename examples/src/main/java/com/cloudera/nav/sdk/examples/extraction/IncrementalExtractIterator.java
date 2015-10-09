/*
 * Copyright (c) 2015 Cloudera, Inc.
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
package com.cloudera.nav.sdk.examples.extraction;

import com.cloudera.nav.sdk.client.ClientUtils;
import com.cloudera.nav.sdk.client.NavApiCient;
import com.cloudera.nav.sdk.client.QueryCriteria;
import com.cloudera.nav.sdk.client.ResultsBatch;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.List;

/**
 *  Custom iterator class used to retrieve entities and relations results from
 * incremental extraction in extractMetadata(). Handles iterating through batches
 * of results with a cursor per query, and iterates through a set of queries.
 */
public class IncrementalExtractIterator implements Iterator<Map<String, Object>> {

  private final NavApiCient client;
  private final Integer limit;
  private final Integer MAX_QUERY_PARTITION_SIZE = 800;
  private final MetadataType type;
  private final String userQuery;
  private boolean hasNext;
  private Iterator<List<String>> partitionIterator;
  private List<Map<String, Object>> resultsBatch;
  private Iterator<Map<String, Object>> resultsBatchIterator;
  private String cursorMark="*";
  private String fullQuery;

  public IncrementalExtractIterator(NavApiCient client,
                                    MetadataType type, String query, Integer limit,
                                    Iterable<String> extractorRunIds){
    this.client = client;
    this.type = type;
    this.userQuery = query;
    this.limit = limit;
    this.partitionIterator =
        Iterables.partition(extractorRunIds,MAX_QUERY_PARTITION_SIZE).iterator();
    if(!Iterables.isEmpty(extractorRunIds)) {
      getNextQuery();
    } else {
      fullQuery = userQuery;
    }
    getNextBatch();
  }

  @Override
  public boolean hasNext() {
    return hasNext;
  }

  @Override
  public Map<String, Object> next() {
    if(!hasNext()){
      throw new NoSuchElementException();
    }
    Map<String, Object> nextResult = resultsBatchIterator.next();
    //if at last element in batch
    if(!resultsBatchIterator.hasNext()){
      //if on last batch
      if(resultsBatch.size()<limit) {
        //if on last query, leave loop
        if (!partitionIterator.hasNext()) {
          hasNext = false;
        //Update query and get next batch
        } else {
          getNextQuery();
          getNextBatch();
        }
      //fetch next batch
      } else {
        getNextBatch();
      }
    }
    return nextResult;
  }

  @VisibleForTesting
  public void getNextBatch(){
    try {
      ResultsBatch<Map<String, Object>> response = getResultsBatch();
      resultsBatch = response.getResults();
      resultsBatchIterator = resultsBatch.iterator();
      hasNext = resultsBatchIterator.hasNext();
      cursorMark = response.getCursorMark();
      if (!hasNext && partitionIterator.hasNext()) {
        getNextQuery();
        getNextBatch();
      }
    } catch (Exception e){
      throw Throwables.propagate(e);
    }
  }

  private ResultsBatch<Map<String, Object>> getResultsBatch() throws EnumConstantNotPresentException{
    QueryCriteria queryCriteria = new QueryCriteria(fullQuery, limit, cursorMark);
      switch(type) {
        case ENTITIES:
          return client.getEntityBatch(queryCriteria);
        case RELATIONS:
          return client.getRelationBatch(queryCriteria);
        default:
          throw new EnumConstantNotPresentException(MetadataType.class, type.name());
      }
  }

  private void getNextQuery(){
    cursorMark="*";
    List<String> extractorRunIdNext = partitionIterator.next();
    String extractorString = ClientUtils.buildConjunctiveClause("extractorRunId",
                                                            extractorRunIdNext);
    fullQuery = ClientUtils.conjoinSolrQueries(userQuery, extractorString);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }
}
