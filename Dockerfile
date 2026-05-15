FROM python:3.11-slim
WORKDIR /app
# Force cache bust: 2026-05-15
ARG CACHEBUST=1
COPY server/ /app/
RUN pip install --no-cache-dir fastapi uvicorn pydantic
# Port is handled by Render's environment variable
CMD ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080"]
