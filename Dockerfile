FROM python:3.9-slim
WORKDIR /app
COPY server/ /app/
RUN pip install --no-cache-dir -r requirements.txt
# Port is handled by Render's environment variable
CMD ["python", "app.py"]
