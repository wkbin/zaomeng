#!/bin/bash

# 设置 OpenAI 兼容 API 端点
export OPENAI_API_BASE="${OPENAI_API_BASE:-https://ai-one.ideaflow.pro/v1}"

# 不要把真实密钥写进仓库；优先复用当前环境变量。
if [ -z "${OPENAI_API_KEY:-}" ]; then
  echo "请先在当前 shell 中设置 OPENAI_API_KEY，再 source 本脚本。"
  echo "示例: export OPENAI_API_KEY='your-key-here'"
  return 1 2>/dev/null || exit 1
fi

# 设置默认模型（根据你的列表，选择适合代码生成的模型）
# gpt-5.3-codex 听起来最适合代码生成任务
export OPENAI_MODEL="${OPENAI_MODEL:-gpt-5.3-codex}"

echo "环境变量已设置："
echo "OPENAI_API_BASE=$OPENAI_API_BASE"
echo "OPENAI_MODEL=$OPENAI_MODEL"
echo "API Key 已设置（隐藏显示）"
