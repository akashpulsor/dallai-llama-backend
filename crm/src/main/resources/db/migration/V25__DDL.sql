UPDATE llm_data
SET business_id=1,  multi_modal=0, model_name='gpt-4o-realtime-preview-2024-10-01', model_url='api.openai.com/v1/realtime?model='
WHERE llm_id=1;

UPDATE llm_data
SET business_id=1,  multi_modal=1, model_name='gpt-4o', model_url='https://api.openai.com/v1'
WHERE llm_id=2;