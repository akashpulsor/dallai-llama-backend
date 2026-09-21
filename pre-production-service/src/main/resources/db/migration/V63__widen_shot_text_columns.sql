-- Widen every VARCHAR(80) text column on shot to TEXT.
--
-- Same class of bug as V63's peopleInFrame Integer fix: the LLM regularly emits values that
-- exceed the field limit ("smartphone with anamorphic adapter and speedbooster" for cine_lens
-- fields, "3-5 stops of ND" for cine_nd_filter, etc). Strict VARCHAR(80) fails the whole
-- insert-shot statement for one long value and throws away 100 KB of otherwise valid output.
-- Pragya's "A Healthier Mumbai Day" hit this after the peopleInFrame fix cleared the first
-- hurdle -- the second insert bombed on a VARCHAR(80).
--
-- No length constraint here at all: LLM prose has no natural upper bound and we've been burned
-- twice already by picking a specific limit. Postgres TEXT has no penalty vs VARCHAR(N) at the
-- storage layer.

ALTER TABLE shot
    ALTER COLUMN subtitle_position           TYPE TEXT,
    ALTER COLUMN cine_lens_focal_length      TYPE TEXT,
    ALTER COLUMN cine_lens_type              TYPE TEXT,
    ALTER COLUMN cine_lens_optical_format    TYPE TEXT,
    ALTER COLUMN cine_headroom               TYPE TEXT,
    ALTER COLUMN cine_lead_room              TYPE TEXT,
    ALTER COLUMN cine_focus_distance         TYPE TEXT,
    ALTER COLUMN cine_depth_of_field         TYPE TEXT,
    ALTER COLUMN cine_movement_speed         TYPE TEXT,
    ALTER COLUMN cine_movement_acceleration  TYPE TEXT,
    ALTER COLUMN cine_aperture               TYPE TEXT,
    ALTER COLUMN cine_iso                    TYPE TEXT,
    ALTER COLUMN cine_shutter                TYPE TEXT,
    ALTER COLUMN cine_nd_filter              TYPE TEXT,
    ALTER COLUMN cine_shutter_angle          TYPE TEXT,
    ALTER COLUMN cine_filtration_nd          TYPE TEXT,
    ALTER COLUMN cine_filtration_polarizer   TYPE TEXT,
    ALTER COLUMN coverage_type               TYPE TEXT,
    ALTER COLUMN screen_direction            TYPE TEXT,
    ALTER COLUMN product_shot_type           TYPE TEXT;
