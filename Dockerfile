# 运行镜像：需 ffmpeg(视频抽帧) + JDK21
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY build/install/constr-asset/ ./
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["./bin/constr-asset"]
