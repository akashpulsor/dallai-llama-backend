-- The client-review conversation and complete director blueprint are owned by
-- creator_scripts.script_payload. Older code copied both trees into every shot
-- plan, multiplying one conversation by the number of shots and forcing
-- Hibernate/Jackson to snapshot the repeated JSON on every plan query.

update creator_script_shot_plans
   set input_payload = input_payload
           - 'clientReview'
           - 'videoDirectorBlueprint'
           - 'masterVideoPrompt'
           - 'perSecondVideoPrompt',
       storyboard_tag = storyboard_tag #- '{revisedShot,masterVideoPrompt}'
 where input_payload ?| array[
           'clientReview',
           'videoDirectorBlueprint',
           'masterVideoPrompt',
           'perSecondVideoPrompt'
       ]
    or storyboard_tag #> '{revisedShot,masterVideoPrompt}' is not null;

with compacted_shots as (
    select id,
           coalesce(jsonb_agg(shot - 'masterVideoPrompt' order by shot_number), '[]'::jsonb) as shots
      from creator_scripts
      cross join lateral jsonb_array_elements(shots)
          with ordinality as script_shot(shot, shot_number)
     where jsonb_typeof(shots) = 'array'
     group by id
)
update creator_scripts as script
   set shots = compacted_shots.shots
  from compacted_shots
 where script.id = compacted_shots.id;

with compacted_payload_shots as (
    select id,
           coalesce(jsonb_agg(shot - 'masterVideoPrompt' order by shot_number), '[]'::jsonb) as shots
      from creator_scripts
      cross join lateral jsonb_array_elements(script_payload -> 'shots')
          with ordinality as payload_shot(shot, shot_number)
     where jsonb_typeof(script_payload -> 'shots') = 'array'
     group by id
)
update creator_scripts as script
   set script_payload = jsonb_set(
           script.script_payload,
           '{shots}',
           compacted_payload_shots.shots,
           false
       )
  from compacted_payload_shots
 where script.id = compacted_payload_shots.id;

update creator_scripts
   set script_payload = script_payload #- '{clientReview,videoDirectorPlan}'
 where jsonb_typeof(script_payload -> 'videoDirectorPlan') = 'object'
   and script_payload #> '{clientReview,videoDirectorPlan}' is not null;
