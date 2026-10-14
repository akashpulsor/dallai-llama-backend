-- The frame a creator chose as their film's thumbnail on the public profile -- one of the shots'
-- generated frames. Null keeps the default: the first shot's finished frame.
ALTER TABLE project ADD COLUMN poster_image_id UUID;
