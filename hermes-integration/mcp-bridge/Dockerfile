FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libpq-dev gcc && \
    rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir "mcp>=1.26,<1.27" requests psycopg2-binary

COPY server.py .

EXPOSE 3001

CMD ["python", "server.py"]
