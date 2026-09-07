-- Geometry is relative to small position blocks. Private order labels are
-- independent of public source offsets and never cross the kernel boundary.
CREATE TABLE position_blocks (
 id INTEGER PRIMARY KEY AUTOINCREMENT, source_id INTEGER NOT NULL,
 kind INTEGER NOT NULL, block_key INTEGER NOT NULL,
 base_key INTEGER NOT NULL, base_byte INTEGER NOT NULL,
 UNIQUE(source_id,kind,block_key)
);
CREATE INDEX position_blocks_anchor ON position_blocks(source_id,kind,base_key);
CREATE INDEX position_blocks_byte ON position_blocks(source_id,kind,base_byte);
CREATE TABLE target_slots (
 id INTEGER PRIMARY KEY AUTOINCREMENT, source_id INTEGER NOT NULL,
 kind INTEGER NOT NULL CHECK(kind IN (0,1,2)),
 local_key INTEGER NOT NULL CHECK(local_key>=0),
 block_id INTEGER NOT NULL, anchor INTEGER NOT NULL,
 map_version INTEGER NOT NULL, start_byte INTEGER NOT NULL,
 end_byte INTEGER NOT NULL CHECK(end_byte>=start_byte), own_ranges TEXT NOT NULL,
 UNIQUE(source_id,kind,local_key)
);
CREATE INDEX target_slots_anchor ON target_slots(block_id,anchor);
CREATE INDEX target_slots_start ON target_slots(block_id,start_byte);
CREATE VIEW index_targets AS SELECT t.id,t.source_id,t.kind,
 t.anchor+b.base_key AS local_key,t.map_version,
 t.start_byte+b.base_byte AS start_byte,t.end_byte+b.base_byte AS end_byte,
 gilune_shift_ranges(t.own_ranges,b.base_byte) AS own_ranges
 FROM target_slots t JOIN position_blocks b ON b.id=t.block_id;
CREATE TABLE index_predicates (
 id INTEGER PRIMARY KEY AUTOINCREMENT, namespace TEXT NOT NULL,
 predicate TEXT NOT NULL, UNIQUE(namespace,predicate)
);
CREATE TABLE prepared_geometry(source_id INTEGER PRIMARY KEY,layout TEXT NOT NULL);
-- Parser arrays are already in source order. Derive initial labels directly
-- from their ordinal: a window over all source maps defeats source restriction
-- in older SQLite planners when this view is entered through a trigger.
CREATE VIEW initial_target_layout AS
SELECT s.id AS source_id,0 AS kind,0 AS local_key,s.map_version,
 0 AS start_byte,json_extract(s.layout,'$.length') AS end_byte,
 json_array(json_array(0,json_extract(s.layout,'$.length'))) AS own_ranges,
 0 AS block_key,0 AS order_key FROM source_maps s
UNION ALL
SELECT s.id,1,json_extract(j.value,'$[0]'),s.map_version,
 json_extract(j.value,'$[1]'),json_extract(j.value,'$[2]'),
 json_array(json_array(json_extract(j.value,'$[1]'),json_extract(j.value,'$[2]'))),
 CAST(j.key AS INTEGER)/64,(CAST(j.key AS INTEGER)+1)*4294967296
 FROM source_maps s,json_each(s.layout,'$.lines') j
UNION ALL
SELECT s.id,2,json_extract(j.value,'$.key'),s.map_version,
 json_extract(j.value,'$.key'),json_extract(j.value,'$.end'),json_extract(j.value,'$.own'),
 CAST(j.key AS INTEGER)/64,(CAST(j.key AS INTEGER)+1)*4294967296
 FROM source_maps s,json_each(s.layout,'$.items') j;
CREATE VIEW source_target_layout AS SELECT source_id,kind,local_key,map_version,start_byte,end_byte,own_ranges FROM initial_target_layout;

CREATE TRIGGER target_binding_delete BEFORE DELETE ON target_slots BEGIN
 UPDATE fact_location_slots SET
  line=CASE WHEN line IS NOT NULL THEN OLD.anchor+(SELECT base_key FROM position_blocks WHERE id=OLD.block_id) END,
  item_key=CASE WHEN item_key IS NOT NULL THEN OLD.anchor+(SELECT base_key FROM position_blocks WHERE id=OLD.block_id) END,
  target_id=NULL WHERE target_id=OLD.id;
 UPDATE text_rows SET line=OLD.anchor+(SELECT base_key FROM position_blocks WHERE id=OLD.block_id),target_id=NULL WHERE target_id=OLD.id;
 UPDATE fact_values SET target_id=NULL WHERE target_id=OLD.id;
END;
CREATE TRIGGER source_targets_delete BEFORE DELETE ON source_maps BEGIN
 DELETE FROM target_slots WHERE source_id=OLD.id;
 DELETE FROM position_blocks WHERE source_id=OLD.id;
END;
CREATE TRIGGER source_targets_before_update BEFORE UPDATE OF rel_path,source_version,layout ON source_maps
WHEN (OLD.rel_path IS NOT NEW.rel_path OR OLD.source_version IS NOT NEW.source_version OR OLD.layout IS NOT NEW.layout)
 AND NOT EXISTS(SELECT 1 FROM prepared_geometry WHERE source_id=OLD.id AND layout=NEW.layout) BEGIN
 UPDATE fact_values SET target_id=NULL WHERE target_id IN (SELECT id FROM target_slots WHERE source_id=OLD.id)
 AND location_id NOT IN (SELECT id FROM fact_locations WHERE rel_path=NEW.rel_path AND source_version=NEW.source_version);
 UPDATE fact_location_slots SET
 line=CASE WHEN line IS NOT NULL THEN (SELECT t.anchor+b.base_key FROM target_slots t JOIN position_blocks b ON b.id=t.block_id WHERE t.id=fact_location_slots.target_id) END,
 item_key=CASE WHEN item_key IS NOT NULL THEN (SELECT t.anchor+b.base_key FROM target_slots t JOIN position_blocks b ON b.id=t.block_id WHERE t.id=fact_location_slots.target_id) END,
 target_id=NULL
 WHERE target_id IN (SELECT id FROM target_slots WHERE source_id=OLD.id)
 AND frame_id NOT IN (SELECT id FROM fact_frames WHERE rel_path=NEW.rel_path AND source_version=NEW.source_version);
 DELETE FROM target_slots WHERE source_id=OLD.id AND OLD.layout IS NOT NEW.layout;
 DELETE FROM position_blocks WHERE source_id=OLD.id AND OLD.layout IS NOT NEW.layout;
END;
CREATE TRIGGER source_targets_insert AFTER INSERT ON source_maps BEGIN
 INSERT INTO position_blocks(source_id,kind,block_key,base_key,base_byte)
 SELECT source_id,kind,block_key,MIN(local_key),MIN(start_byte)
 FROM initial_target_layout WHERE source_id=NEW.id GROUP BY kind,block_key;
 INSERT INTO target_slots(source_id,kind,local_key,block_id,anchor,map_version,start_byte,end_byte,own_ranges)
 SELECT l.source_id,l.kind,l.order_key,b.id,l.local_key-b.base_key,l.map_version,
 l.start_byte-b.base_byte,l.end_byte-b.base_byte,gilune_shift_ranges(l.own_ranges,-b.base_byte)
 FROM initial_target_layout l JOIN position_blocks b ON b.source_id=l.source_id AND b.kind=l.kind AND b.block_key=l.block_key
 WHERE l.source_id=NEW.id;
 UPDATE fact_values SET target_id=(SELECT t.id FROM fact_locations l JOIN position_blocks b ON b.id=(SELECT id FROM position_blocks WHERE source_id=NEW.id
 AND kind=CASE WHEN l.item_key IS NOT NULL THEN 2 WHEN l.line IS NOT NULL THEN 1 ELSE 0 END
 AND base_key<=COALESCE(l.item_key,l.line,0) ORDER BY base_key DESC LIMIT 1)
 JOIN target_slots t ON t.block_id=b.id AND t.anchor=COALESCE(l.item_key,l.line,0)-b.base_key
 WHERE l.id=fact_values.location_id AND NOT(l.item_key IS NOT NULL AND l.line IS NOT NULL))
 WHERE target_id IS NULL AND location_id IN (SELECT id FROM fact_locations WHERE rel_path=NEW.rel_path AND source_version=NEW.source_version);
END;
CREATE TRIGGER source_targets_update AFTER UPDATE OF rel_path,source_version,layout ON source_maps
WHEN (OLD.rel_path IS NOT NEW.rel_path OR OLD.source_version IS NOT NEW.source_version OR OLD.layout IS NOT NEW.layout)
 AND NOT EXISTS(SELECT 1 FROM prepared_geometry WHERE source_id=NEW.id AND layout=NEW.layout) BEGIN
 INSERT INTO position_blocks(source_id,kind,block_key,base_key,base_byte)
 SELECT source_id,kind,block_key,MIN(local_key),MIN(start_byte)
 FROM initial_target_layout WHERE source_id=NEW.id AND OLD.layout IS NOT NEW.layout GROUP BY kind,block_key;
 INSERT INTO target_slots(source_id,kind,local_key,block_id,anchor,map_version,start_byte,end_byte,own_ranges)
 SELECT l.source_id,l.kind,l.order_key,b.id,l.local_key-b.base_key,l.map_version,
 l.start_byte-b.base_byte,l.end_byte-b.base_byte,gilune_shift_ranges(l.own_ranges,-b.base_byte)
 FROM initial_target_layout l JOIN position_blocks b ON b.source_id=l.source_id AND b.kind=l.kind AND b.block_key=l.block_key
 WHERE l.source_id=NEW.id AND OLD.layout IS NOT NEW.layout;
 UPDATE fact_values SET target_id=(SELECT t.id FROM fact_locations l JOIN position_blocks b ON b.id=(SELECT id FROM position_blocks WHERE source_id=NEW.id
 AND kind=CASE WHEN l.item_key IS NOT NULL THEN 2 WHEN l.line IS NOT NULL THEN 1 ELSE 0 END
 AND base_key<=COALESCE(l.item_key,l.line,0) ORDER BY base_key DESC LIMIT 1)
 JOIN target_slots t ON t.block_id=b.id AND t.anchor=COALESCE(l.item_key,l.line,0)-b.base_key
 WHERE l.id=fact_values.location_id AND NOT(l.item_key IS NOT NULL AND l.line IS NOT NULL))
 WHERE target_id IS NULL AND location_id IN (SELECT id FROM fact_locations WHERE rel_path=NEW.rel_path AND source_version=NEW.source_version);
END;
