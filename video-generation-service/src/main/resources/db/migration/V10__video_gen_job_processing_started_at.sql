-- Needed to detect a PROCESSING row stranded by a pod crash mid-dispatch: without this, once
-- markProcessing() commits (see VideoGenJobPersistenceService), nothing ever revisits the row if
-- the pod dies before finishSuccess/finishFailure runs -- it would stay PROCESSING forever.
ALTER TABLE video_gen_job ADD COLUMN processing_started_at TIMESTAMPTZ;

CREATE INDEX idx_video_gen_job_status_processing_started ON video_gen_job (status, processing_started_at);
