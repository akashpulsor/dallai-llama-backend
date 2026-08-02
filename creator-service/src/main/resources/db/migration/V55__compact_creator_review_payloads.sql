-- Bound creator review JSON that was historically amplified by storing full proposals
-- in chat history and then copying that history into every undo snapshot.

with review_rows as (
    select id,
           script_payload #> '{clientReview,reviewChat}' as review_chat
      from creator_scripts
     where jsonb_typeof(script_payload #> '{clientReview,reviewChat}') = 'array'
), compacted_chats as (
    select review_rows.id,
           coalesce(
               jsonb_agg(
                   case
                       when message_number > greatest(jsonb_array_length(review_chat) - 4, 0)
                           then message
                       else message - 'proposal' - 'generatedFrames' - 'retrievedKeys'
                   end
                   order by message_number
               ) filter (
                   where message_number > greatest(jsonb_array_length(review_chat) - 24, 0)
               ),
               '[]'::jsonb
           ) as review_chat
      from review_rows
      cross join lateral jsonb_array_elements(review_rows.review_chat)
          with ordinality as chat_message(message, message_number)
     group by review_rows.id, review_rows.review_chat
)
update creator_scripts as script
   set script_payload = jsonb_set(
           script.script_payload,
           '{clientReview,reviewChat}',
           compacted_chats.review_chat,
           false
       )
  from compacted_chats
 where script.id = compacted_chats.id;

with snapshot_rows as (
    select id,
           script_payload #> '{clientReviewPlanningSnapshots}' as snapshots
      from creator_scripts
     where jsonb_typeof(script_payload #> '{clientReviewPlanningSnapshots}') = 'array'
), compacted_snapshots as (
    select snapshot_rows.id,
           coalesce(
               jsonb_agg(
                   case
                       when jsonb_typeof(snapshot #> '{planningPayload,clientReview}') = 'object'
                           then jsonb_set(
                               snapshot,
                               '{planningPayload,clientReview}',
                               (snapshot #> '{planningPayload,clientReview}')
                                   - 'reviewChat'
                                   - 'conversationMemory'
                                   - 'updatedAt'
                                   - 'updatedBy',
                               false
                           ) || jsonb_build_object('clientReviewChatExcluded', true)
                       else snapshot
                   end
                   order by snapshot_number
               ),
               '[]'::jsonb
           ) as snapshots
      from snapshot_rows
      cross join lateral jsonb_array_elements(snapshot_rows.snapshots)
          with ordinality as planning_snapshot(snapshot, snapshot_number)
     group by snapshot_rows.id
)
update creator_scripts as script
   set script_payload = jsonb_set(
           script.script_payload,
           '{clientReviewPlanningSnapshots}',
           compacted_snapshots.snapshots,
           false
       )
  from compacted_snapshots
 where script.id = compacted_snapshots.id;
