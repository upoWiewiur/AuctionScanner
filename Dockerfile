FROM python:3.11-slim
WORKDIR /app
COPY server/ /app/
RUN pip install --no-cache-dir fastapi uvicorn pydantic
# Port is handled by Render's environment variable
CMD ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080"]
