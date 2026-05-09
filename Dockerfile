FROM python:3.9-slim
WORKDIR /app
COPY server/ /app/
RUN pip install --no-cache-dir -r requirements.txt
ENV PORT=10000
EXPOSE 10000
CMD ["python", "app.py"]
