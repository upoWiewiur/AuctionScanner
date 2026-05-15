# Use official Python image
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Copy all files to the container
COPY . .

# Install dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Cloud platforms like Render use the PORT environment variable
ENV PORT=8080
EXPOSE 8080

# Start the server
CMD ["python", "app.py"]
