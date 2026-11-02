-- Jewellery videos for jewellery brands' mail (their titles read as the jewellery theme, MailThemes):
-- real brand ads from Pinterest video search, checked to have a playable 720p video and a picture.
-- InspirationMediaJob fills each one's picture, video and maker shortly after startup.
INSERT INTO inspiration_item (id, pin_id, title, industry, sort_order) VALUES
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a01', '1033435445755548335', 'Minimal jewellery reel: when minimal meets magical', 'FASHION', 20),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a02', '678002918935767031', 'Antique temple necklace sets, close-up details', 'FASHION', 21),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a03', '1146588386408939983', 'Jewellery reel that reminds you why you buy', 'FASHION', 22),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a04', '158189005657995211', 'Festive gold jewellery edit, a brand film', 'FASHION', 23),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a05', '663999538851170914', 'Bridal jewellery for the modern bride', 'FASHION', 24),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a06', '1096696946780042447', 'Demi-fine jewellery ad: the sound of good taste', 'FASHION', 25),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a07', '883268545651318498', 'Golden bangles, tradition in motion', 'FASHION', 26),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a08', '757730706102272182', '22kt gold jewellery steeped in culture', 'FASHION', 27),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a09', '494059021635667874', 'Gold and diamond jewellery launch film', 'FASHION', 28),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a10', '290482244738522320', 'Stacked bands and diamond bracelets, effortless glamour', 'FASHION', 29),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a11', '63965257210073093', 'Silver ring product shot, minimal and elegant', 'FASHION', 30),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a12', '472878029646384865', '22K bridal jewellery set with choker and jhumka', 'FASHION', 31),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a13', '901705156655363267', 'Lab-grown diamond earrings, elegance meets ethics', 'FASHION', 32),
('7c0f1a52-6b1e-4d3e-9a41-0c2f5e7d1a14', '16607092372311526', 'Traditional earrings reel, shaping trends', 'FASHION', 33)
ON CONFLICT (pin_id) DO NOTHING;
