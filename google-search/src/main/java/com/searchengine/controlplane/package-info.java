/**
 * Control plane: shard map, replication, and scaling. Owns the deterministic Doc_ID -> Shard
 * mapping ({@code shardFor(docId) = hash(docId) mod shardCount}) and the consistent-hash shard
 * map that minimizes remapping on scale-out (design §4 and "Sharding, Partitioning, and
 * Replication Strategy", Requirements 5.7, 14.1, 14.3).
 */
package com.searchengine.controlplane;
