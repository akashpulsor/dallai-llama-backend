-- A request still unpaid after too long can be expired by its creator or by ops: its brief link
-- stops working and it leaves the queues.
ALTER TABLE lead_brand_inquiry DROP CONSTRAINT lead_brand_inquiry_status_ck;
ALTER TABLE lead_brand_inquiry ADD CONSTRAINT lead_brand_inquiry_status_ck
    CHECK (status IN ('NEW', 'VIEWED', 'CONVERTED', 'DECLINED', 'EXPIRED'));
