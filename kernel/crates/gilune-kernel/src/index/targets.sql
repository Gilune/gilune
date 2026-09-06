-- Source-qualified targets and private predicate identifiers. Fact values and
-- legacy coordinate projection are defined in storage.sql.
CREATE TABLE index_targets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id INTEGER NOT NULL,
    kind INTEGER NOT NULL CHECK(kind IN (0,1,2)),
    local_key INTEGER NOT NULL CHECK(local_key>=0),
    map_version INTEGER NOT NULL,
    start_byte INTEGER NOT NULL CHECK(start_byte>=0),
    end_byte INTEGER NOT NULL CHECK(end_byte>=start_byte),
    own_ranges TEXT NOT NULL,
    UNIQUE(source_id,kind,local_key)
);
CREATE TABLE index_predicates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    namespace TEXT NOT NULL,
    predicate TEXT NOT NULL,
    UNIQUE(namespace,predicate)
);
-- Bounded snippet reconstruction locates physical lines by original byte range.
CREATE INDEX index_targets_source_start ON index_targets(source_id,kind,start_byte);
-- Each arm can be restricted to a single source. This is a metadata projection,
-- not a second copy of document prose and not a query-time Markdown parser.
CREATE VIEW source_target_layout AS
SELECT s.id AS source_id,0 AS kind,0 AS local_key,
    s.map_version AS map_version,0 AS start_byte,
    json_extract(s.layout,'$.length') AS end_byte,
    json_array(json_array(0,json_extract(s.layout,'$.length'))) AS own_ranges
FROM source_maps s
UNION ALL
SELECT s.id,1,json_extract(j.value,'$[0]'),
    s.map_version,json_extract(j.value,'$[1]'),
    json_extract(j.value,'$[2]'),
    json_array(json_array(json_extract(j.value,'$[1]'),json_extract(j.value,'$[2]')))
FROM source_maps s,json_each(s.layout,'$.lines') j
UNION ALL
SELECT s.id,2,json_extract(j.value,'$.key'),
    s.map_version,json_extract(j.value,'$.key'),
    json_extract(j.value,'$.end'),json_extract(j.value,'$.own')
FROM source_maps s,json_each(s.layout,'$.items') j;

-- A removed snapshot cannot keep live target bindings. Preserve stale facts
-- themselves: a lagging producer must retain its previous evidence and failure.
CREATE TRIGGER source_targets_delete BEFORE DELETE ON source_maps BEGIN
    UPDATE fact_values SET target_id=NULL WHERE target_id IN
        (SELECT id FROM index_targets WHERE source_id=OLD.id);
    DELETE FROM index_targets WHERE source_id=OLD.id;
END;
CREATE TRIGGER source_targets_before_update BEFORE UPDATE OF rel_path,source_version,layout ON source_maps BEGIN
    UPDATE fact_values SET target_id=NULL WHERE target_id IN
        (SELECT id FROM index_targets WHERE source_id=OLD.id);
    DELETE FROM index_targets WHERE source_id=OLD.id;
END;
CREATE TRIGGER source_targets_insert AFTER INSERT ON source_maps BEGIN
    INSERT INTO index_targets(source_id,kind,local_key,map_version,start_byte,end_byte,own_ranges)
        SELECT * FROM source_target_layout WHERE source_id=NEW.id;
    UPDATE fact_values SET target_id=(SELECT t.id FROM index_targets t JOIN fact_locations l ON l.id=fact_values.location_id
        WHERE t.source_id=NEW.id
        AND t.kind=CASE WHEN l.item_key IS NOT NULL THEN 2 WHEN l.line IS NOT NULL THEN 1 ELSE 0 END
        AND t.local_key=COALESCE(l.item_key,l.line,0)
        AND NOT (l.item_key IS NOT NULL AND l.line IS NOT NULL))
        WHERE location_id IN (SELECT id FROM fact_locations WHERE rel_path=NEW.rel_path AND source_version=NEW.source_version);
END;
CREATE TRIGGER source_targets_update AFTER UPDATE OF rel_path,source_version,layout ON source_maps BEGIN
    INSERT INTO index_targets(source_id,kind,local_key,map_version,start_byte,end_byte,own_ranges)
        SELECT * FROM source_target_layout WHERE source_id=NEW.id;
    UPDATE fact_values SET target_id=(SELECT t.id FROM index_targets t JOIN fact_locations l ON l.id=fact_values.location_id
        WHERE t.source_id=NEW.id
        AND t.kind=CASE WHEN l.item_key IS NOT NULL THEN 2 WHEN l.line IS NOT NULL THEN 1 ELSE 0 END
        AND t.local_key=COALESCE(l.item_key,l.line,0)
        AND NOT (l.item_key IS NOT NULL AND l.line IS NOT NULL))
        WHERE location_id IN (SELECT id FROM fact_locations WHERE rel_path=NEW.rel_path AND source_version=NEW.source_version);
END;

