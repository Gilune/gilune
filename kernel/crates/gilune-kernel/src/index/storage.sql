-- One shared revision frame per path/version, one location per anchor. Public
-- anchor queries use the relational projection below; no wide fact copy exists.
CREATE INDEX index_predicates_name ON index_predicates(predicate,id);
CREATE TABLE fact_frames (
    id INTEGER PRIMARY KEY,
    rel_path TEXT NOT NULL,
    source_version TEXT
);
CREATE UNIQUE INDEX fact_frames_identity ON fact_frames
    (rel_path, COALESCE(source_version,''));
CREATE TABLE fact_location_slots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    frame_id INTEGER NOT NULL,
    target_id INTEGER,
    line INTEGER,
    item_key INTEGER
);
CREATE UNIQUE INDEX fact_location_slots_identity ON fact_location_slots
    (frame_id, COALESCE(target_id,-1), COALESCE(line,-1), COALESCE(item_key,-1));
CREATE INDEX fact_location_slots_target ON fact_location_slots(target_id);
CREATE INDEX fact_location_slots_literal ON fact_location_slots(frame_id,line,item_key) WHERE target_id IS NULL;
CREATE VIEW fact_locations AS SELECT l.id,r.rel_path,r.source_version,
 CASE WHEN l.line IS NOT NULL THEN COALESCE(t.anchor+b.base_key,l.line) END AS line,
 CASE WHEN l.item_key IS NOT NULL THEN COALESCE(t.anchor+b.base_key,l.item_key) END AS item_key
 FROM fact_location_slots l JOIN fact_frames r ON r.id=l.frame_id
 LEFT JOIN target_slots t ON t.id=l.target_id LEFT JOIN position_blocks b ON b.id=t.block_id;
CREATE TRIGGER fact_locations_insert INSTEAD OF INSERT ON fact_locations BEGIN
 INSERT OR IGNORE INTO fact_frames(rel_path,source_version) VALUES(NEW.rel_path,NEW.source_version);
 INSERT OR IGNORE INTO fact_location_slots(id,frame_id,target_id,line,item_key)
 SELECT NEW.id,r.id,t.id,
 CASE WHEN NEW.line IS NOT NULL THEN CASE WHEN t.id IS NULL THEN NEW.line ELSE 0 END END,
 CASE WHEN NEW.item_key IS NOT NULL THEN CASE WHEN t.id IS NULL THEN NEW.item_key ELSE 0 END END
 FROM fact_frames r LEFT JOIN source_maps s ON s.rel_path=r.rel_path AND s.source_version=r.source_version
 LEFT JOIN position_blocks b ON b.id=(SELECT id FROM position_blocks WHERE source_id=s.id
 AND kind=CASE WHEN NEW.item_key IS NOT NULL THEN 2 WHEN NEW.line IS NOT NULL THEN 1 ELSE 0 END
 AND base_key<=COALESCE(NEW.item_key,NEW.line,0) ORDER BY base_key DESC LIMIT 1)
 LEFT JOIN target_slots t ON t.block_id=b.id AND t.anchor=COALESCE(NEW.item_key,NEW.line,0)-b.base_key
 AND NOT(NEW.item_key IS NOT NULL AND NEW.line IS NOT NULL)
 WHERE r.rel_path=NEW.rel_path AND COALESCE(r.source_version,'')=COALESCE(NEW.source_version,'')
 AND NOT EXISTS(SELECT 1 FROM fact_location_slots l WHERE l.frame_id=r.id AND l.target_id IS NULL AND COALESCE(l.line,-1)=COALESCE(NEW.line,-1) AND COALESCE(l.item_key,-1)=COALESCE(NEW.item_key,-1));
END;
CREATE TRIGGER fact_locations_update INSTEAD OF UPDATE ON fact_locations BEGIN
    INSERT OR IGNORE INTO fact_frames(rel_path,source_version) VALUES(NEW.rel_path,NEW.source_version);
    UPDATE fact_location_slots SET frame_id=(SELECT id FROM fact_frames
        WHERE rel_path=NEW.rel_path AND COALESCE(source_version,'')=COALESCE(NEW.source_version,'')),
        line=NEW.line,item_key=NEW.item_key,target_id=NULL WHERE id=OLD.id;
END;
CREATE TRIGGER fact_locations_delete INSTEAD OF DELETE ON fact_locations BEGIN
    DELETE FROM fact_location_slots WHERE id=OLD.id;
END;
CREATE TABLE fact_values (
    id INTEGER PRIMARY KEY,
    location_id INTEGER NOT NULL,
    predicate_id INTEGER NOT NULL,
    target_id INTEGER,
    value TEXT NOT NULL,
    num REAL,
    typed INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX fact_values_location ON fact_values(location_id,predicate_id);
CREATE INDEX facts_compact_target_value ON fact_values(target_id,predicate_id,value,num,typed);
CREATE INDEX facts_compact_predicate_value ON fact_values(predicate_id,value,target_id);
CREATE INDEX facts_compact_predicate_folded ON fact_values(predicate_id,gilune_fold(value),target_id);
CREATE INDEX facts_compact_predicate_num ON fact_values(predicate_id,num,target_id);
CREATE VIEW facts AS SELECT f.id AS rowid,l.rel_path,l.line,l.item_key,
    l.source_version,p.namespace,p.predicate,f.value,f.num,f.typed,
    f.target_id,f.predicate_id
    FROM fact_values f JOIN fact_locations l ON l.id=f.location_id
    JOIN index_predicates p ON p.id=f.predicate_id;

-- Compatibility mutations are used by anchor fixtures and identity maintenance.
-- Normal producer batches write the compact tables directly, without triggers.
CREATE TRIGGER facts_insert INSTEAD OF INSERT ON facts BEGIN
    INSERT OR IGNORE INTO index_predicates(namespace,predicate) VALUES(NEW.namespace,NEW.predicate);
    INSERT OR IGNORE INTO fact_locations(rel_path,source_version,line,item_key)
        VALUES(NEW.rel_path,NEW.source_version,NEW.line,NEW.item_key);
    INSERT INTO fact_values(location_id,predicate_id,target_id,value,num,typed)
        SELECT l.id,p.id,(SELECT t.id FROM source_maps s JOIN position_blocks b ON b.id=(SELECT id FROM position_blocks WHERE source_id=s.id
            AND kind=CASE WHEN NEW.item_key IS NOT NULL THEN 2 WHEN NEW.line IS NOT NULL THEN 1 ELSE 0 END
            AND base_key<=COALESCE(NEW.item_key,NEW.line,0) ORDER BY base_key DESC LIMIT 1)
            JOIN target_slots t ON t.block_id=b.id AND t.anchor=COALESCE(NEW.item_key,NEW.line,0)-b.base_key
            WHERE s.rel_path=NEW.rel_path AND s.source_version=NEW.source_version
            AND NOT (NEW.item_key IS NOT NULL AND NEW.line IS NOT NULL)),
            NEW.value,NEW.num,COALESCE(NEW.typed,1)
        FROM fact_locations l,index_predicates p WHERE l.rel_path=NEW.rel_path
            AND COALESCE(l.source_version,'')=COALESCE(NEW.source_version,'')
            AND COALESCE(l.line,-1)=COALESCE(NEW.line,-1)
            AND COALESCE(l.item_key,-1)=COALESCE(NEW.item_key,-1)
            AND p.namespace=NEW.namespace AND p.predicate=NEW.predicate;
END;
CREATE TRIGGER facts_delete INSTEAD OF DELETE ON facts BEGIN
    DELETE FROM fact_values WHERE id=OLD.rowid;
END;
CREATE TRIGGER facts_update INSTEAD OF UPDATE ON facts BEGIN
    INSERT OR IGNORE INTO index_predicates(namespace,predicate) VALUES(NEW.namespace,NEW.predicate);
    INSERT OR IGNORE INTO fact_locations(rel_path,source_version,line,item_key)
        VALUES(NEW.rel_path,NEW.source_version,NEW.line,NEW.item_key);
    UPDATE fact_values SET location_id=(SELECT id FROM fact_locations WHERE rel_path=NEW.rel_path
        AND COALESCE(source_version,'')=COALESCE(NEW.source_version,'')
        AND COALESCE(line,-1)=COALESCE(NEW.line,-1) AND COALESCE(item_key,-1)=COALESCE(NEW.item_key,-1)),
        predicate_id=(SELECT id FROM index_predicates WHERE namespace=NEW.namespace AND predicate=NEW.predicate),
        target_id=(SELECT t.id FROM source_maps s JOIN position_blocks b ON b.id=(SELECT id FROM position_blocks WHERE source_id=s.id
            AND kind=CASE WHEN NEW.item_key IS NOT NULL THEN 2 WHEN NEW.line IS NOT NULL THEN 1 ELSE 0 END
            AND base_key<=COALESCE(NEW.item_key,NEW.line,0) ORDER BY base_key DESC LIMIT 1)
            JOIN target_slots t ON t.block_id=b.id AND t.anchor=COALESCE(NEW.item_key,NEW.line,0)-b.base_key
            WHERE s.rel_path=NEW.rel_path AND s.source_version=NEW.source_version
            AND NOT (NEW.item_key IS NOT NULL AND NEW.line IS NOT NULL)),
        value=NEW.value,num=NEW.num,typed=NEW.typed WHERE id=OLD.rowid;
END;
-- Line history (ADR 0025): one object's replayed revisions, cached so the
-- next change extends it rather than replaying from the beginning.
CREATE TABLE line_history (
    object_id TEXT PRIMARY KEY,
    revisions INTEGER NOT NULL,
    last_created INTEGER NOT NULL,
    last_revision TEXT NOT NULL,
    lines TEXT NOT NULL
);
