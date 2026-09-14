-- Clears the resolution that the old shot-list prompt taught the model to write into
-- cine_capture_format.
--
-- Version 6 of PRE_PROD_SHOT_LIST_GENERATE offered "e.g. 4K 10-bit log, standard 1080p" as the
-- example and the model copied it verbatim, so existing projects have every shot claiming a
-- resolution the render never uses -- one real project has "standard 1080p" on all thirteen shots
-- while rendering at 480p. llm-gateway V89 fixes the prompt so new shots stop arriving this way;
-- this fixes the rows that already did.
--
-- Only the resolution token is removed. The rest of captureFormat is genuine look information
-- (codec, bit depth, colour profile) and is kept: "4K 10-bit log" becomes "10-bit log", which is
-- the part that was ever true.
--
-- A value that was ONLY a resolution ("standard 1080p" -> "standard") is nulled rather than left
-- as a dangling adjective, since "standard" on its own says nothing about the look.
UPDATE shot
SET cine_capture_format = NULLIF(
        BTRIM(
            REGEXP_REPLACE(
                REGEXP_REPLACE(cine_capture_format, '(?i)\y(\d{3,4}p|[248]k)\y', '', 'g'),
                '\s{2,}', ' ', 'g'
            ),
            ' ,;'
        ),
        ''
    )
WHERE cine_capture_format ~* '\y(\d{3,4}p|[248]k)\y';

-- "standard", "standard ," and friends are what's left when the value was nothing but a
-- resolution qualifier. Nothing downstream can use them.
UPDATE shot
SET cine_capture_format = NULL
WHERE cine_capture_format IS NOT NULL
  AND BTRIM(LOWER(cine_capture_format), ' ,;') IN ('standard', 'standard dynamic range video', '');
