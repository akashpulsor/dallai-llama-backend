-- Creator's manual gate for the client's ability to download the assembled final video from
-- the public review page (Project entity javadoc has the full story). Defaults to false --
-- every existing project stays locked until the creator explicitly flips it. The client can
-- still PREVIEW the video regardless; this column gates the download link only.
ALTER TABLE project
    ADD COLUMN final_video_download_unlocked BOOLEAN NOT NULL DEFAULT FALSE;
