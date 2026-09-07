param(
    [Parameter(Mandatory=$true)][string]$InputLog,
    [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$groups = [System.Collections.Generic.List[object]]::new()
$events = [System.Collections.Generic.List[object]]::new()
$edits = [System.Collections.Generic.List[object]]::new()
foreach ($line in Get-Content -LiteralPath $InputLog) {
    if ($line -match '^\s*UPDATE_PROFILE (.*)$') {
        $event = $Matches[1] | ConvertFrom-Json
        $events.Add($event)
        if ($event.phase -eq 'reconcile_total') {
            if (@($events | Where-Object { $_.phase -in @('namespace_batch', 'publish_ready_page') }).Count -gt 0) {
                $groups.Add([pscustomobject]@{ total_ms=[double]$event.ms; events=@($events.ToArray()); used=$false })
            }
            $events.Clear()
        }
    }
    if ($line -match 'SHAPE_EDIT items_per_document=(\d+) sample=(\d+) reconcile_ms=([\d.]+) settled_advance_ms=([\d.]+) callbacks_ms=\{(.*)\}') {
        $callbacks = @{}
        foreach ($entry in $Matches[5].Split(',')) {
            if ([string]::IsNullOrWhiteSpace($entry)) { continue }
            $pair = $entry.Trim().Split('=')
            $callbacks[$pair[0]] = [double]::Parse($pair[1], [cultureinfo]::InvariantCulture)
        }
        $edits.Add([pscustomobject]@{
            items_per_document=[int]$Matches[1]; sample=[int]$Matches[2]
            reconcile_ms=[double]::Parse($Matches[3], [cultureinfo]::InvariantCulture)
            settled_advance_ms=[double]::Parse($Matches[4], [cultureinfo]::InvariantCulture)
            callbacks_ms=$callbacks
        })
    }
}
# Gradle buffers stdout and stderr separately. Do not place native events between
# stdout BEGIN/RESTORE markers. Native reconcile_total closes an ordered stderr
# group; match it to its independently measured Kotlin call with a tight bound.
$results = foreach ($edit in $edits) {
    $group = $groups | Where-Object { -not $_.used } |
        Sort-Object { [math]::Abs($_.total_ms - $edit.reconcile_ms) } | Select-Object -First 1
    if (-not $group) { throw 'No native reconcile group for edit' }
    $delta = [math]::Abs($group.total_ms - $edit.reconcile_ms)
    if ($delta -gt 2.0) { throw "Native/Kotlin call pairing exceeds 2 ms: $delta" }
    $group.used = $true
    $sum = { param($phase) ($group.events | Where-Object { $_.phase -eq $phase } | Measure-Object -Property ms -Sum).Sum }
    [pscustomobject]@{
        items_per_document=$edit.items_per_document; sample=$edit.sample
        kotlin_reconcile_ms=$edit.reconcile_ms; native_reconcile_ms=$group.total_ms; pairing_delta_ms=$delta
        reconcile_before_index_ms=(& $sum 'reconcile_before_index')
        source_read_split_ms=(& $sum 'read_and_split_source')
        snapshot_parse_ms=(& $sum 'source_snapshot_parse')
        plugin_adapters_ms=(& $sum 'plugin_call_including_adapter')
        plugin_bodies_ms=$edit.callbacks_ms
        validation_ms=(& $sum 'validate_scoped_facts')
        storage_transactions_ms=(& $sum 'commit_plan')
        durable_commits_ms=(& $sum 'durable_commit')
        fact_insert_ms=(& $sum 'insert_facts_receipt_indexes')
        fact_diff_ms=(& $sum 'diff_facts_receipt_indexes')
        local_input_diff_ms=(& $sum 'local_input_diff')
        publication_ms=(& $sum 'publish_ready_page')
        shared_locations_ms=(& $sum 'advance_shared_locations')
        coordinate_slots_ms=(& $sum 'advance_coordinate_slots')
        text_fts_insert_ms=(& $sum 'insert_text_rows_fts')
        text_fts_diff_ms=(& $sum 'diff_text_rows_fts')
        source_map_replace_ms=(& $sum 'replace_source_map_targets')
        source_map_insert_ms=(& $sum 'insert_source_map_targets')
        source_map_delete_ms=(& $sum 'delete_source_map_targets')
        all_events=$group.events
    }
}
if (@($results).Count -ne $edits.Count -or $edits.Count -eq 0) { throw 'Incomplete profile' }
$results | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding utf8
$results | Select-Object items_per_document,sample,kotlin_reconcile_ms,snapshot_parse_ms,fact_insert_ms,storage_transactions_ms | Format-Table
