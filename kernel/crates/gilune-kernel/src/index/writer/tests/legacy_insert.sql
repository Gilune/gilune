CREATE TRIGGER fact_ids_insert AFTER INSERT ON facts BEGIN
    INSERT INTO index_predicates(namespace,predicate)
        SELECT NEW.namespace,NEW.predicate WHERE NOT EXISTS
        (SELECT 1 FROM index_predicates WHERE namespace=NEW.namespace AND predicate=NEW.predicate);
    UPDATE facts SET
        predicate_id=(SELECT id FROM index_predicates WHERE namespace=NEW.namespace AND predicate=NEW.predicate),
        target_id=(SELECT t.id FROM source_maps s JOIN index_targets t ON t.source_id=s.id
            WHERE s.rel_path=NEW.rel_path AND s.source_version=NEW.source_version
            AND t.kind=CASE WHEN NEW.item_key IS NOT NULL THEN 2 WHEN NEW.line IS NOT NULL THEN 1 ELSE 0 END
            AND t.local_key=COALESCE(NEW.item_key,NEW.line,0)
            AND NOT (NEW.item_key IS NOT NULL AND NEW.line IS NOT NULL))
        WHERE rowid=NEW.rowid;
END;
