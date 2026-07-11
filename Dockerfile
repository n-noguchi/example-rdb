FROM maven:3.9-eclipse-temurin-17

WORKDIR /app

# Maven設定ディレクトリ
ENV MAVEN_CONFIG=/root/.m2

# 日本語ロケール対応（テスト出力用）
ENV LANG=C.UTF-8

# デフォルトはbashを起動（compose経由でコマンド上書き）
CMD ["bash"]
