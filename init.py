#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
造梦.skill 项目初始化脚本
"""

import sys
from pathlib import Path


def init_project():
    """初始化项目结构"""
    
    # 项目根目录
    root = Path.cwd()
    
    print("=== 造梦.skill 项目初始化 ===")
    
    # 创建目录结构
    dirs_to_create = [
        "data/characters",
        "data/relations", 
        "data/sessions",
        "data/corrections",
        "src/core",
        "src/modules",
        "src/utils",
        "tests",
        "logs"
    ]
    
    for dir_path in dirs_to_create:
        full_path = root / dir_path
        full_path.mkdir(parents=True, exist_ok=True)
        print(f"创建目录: {dir_path}")
    
    # 创建 __init__.py 文件
    init_files = [
        "src/__init__.py",
        "src/core/__init__.py",
        "src/modules/__init__.py", 
        "src/utils/__init__.py",
        "tests/__init__.py"
    ]
    
    for init_file in init_files:
        full_path = root / init_file
        if not full_path.exists():
            with open(full_path, 'w') as f:
                f.write('')
            print(f"创建文件: {init_file}")
    
    # 检查配置文件
    config_example = root / "config.yaml.example"
    if not config_example.exists():
        print("错误: config.yaml.example 不存在")
        print("请确保项目包含配置文件模板")
        return False
    
    # 提示用户配置
    print("\n=== 下一步操作 ===")
    print("1. 复制配置文件:")
    print("   cp config.yaml.example config.yaml")
    print("\n2. 编辑 config.yaml，设置:")
    print("   - OpenAI 兼容 API 端点")
    print("   - API Key")
    print("   - 模型名称 (如 gpt-5.3-codex)")
    print("\n3. 安装依赖:")
    print("   pip install -r requirements.txt")
    print("\n4. 运行测试:")
    print("   python -m pytest tests/")
    
    return True


if __name__ == "__main__":
    try:
        success = init_project()
        if success:
            print("\n✅ 项目初始化完成！")
            sys.exit(0)
        else:
            print("\n❌ 项目初始化失败")
            sys.exit(1)
    except Exception as e:
        print(f"\n错误: {e}")
        sys.exit(1)
