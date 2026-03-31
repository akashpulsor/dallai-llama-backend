FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends espeak-ng ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY kokoro_server/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY kokoro_server/app.py /app/app.py

EXPOSE 5006

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "5006"]
