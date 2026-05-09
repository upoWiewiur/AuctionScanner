# Use official Python image
FROM python:3.9-slim

# Set working directory
WORKDIR /app

# Copy all files to the container
COPY . .

# Cloud platforms like Render use the PORT environment variable
ENV PORT=8080
EXPOSE 8080

# Start the server
CMD ["python", "app.py"]
