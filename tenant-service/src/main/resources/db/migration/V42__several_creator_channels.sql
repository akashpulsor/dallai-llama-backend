-- A creator may connect several of their own YouTube channels and choose which one each film goes
-- to. The channel on their profile stays the one verified first (creator_youtube_channel, one per
-- tenant); the others are places to publish. A channel still has one live owner of each kind
-- (ux_youtube_connection_channel), so two creators can never connect the same channel.
DROP INDEX IF EXISTS ux_youtube_connection_creator;
