from flask import Flask, jsonify, request
from video_processor import VideoProcessor, Strategy
import os

app = Flask(__name__)

# 初始化 VideoProcessor
processor = VideoProcessor(
    api_key="7127d0a4-41ea-4f7b-9a37-92d302011e08",  # 替换为你的 API Key
    model_id="doubao-1-5-vision-pro-32k-250115"       # 替换为你的 Model ID
)

# 确保上传目录存在
UPLOAD_FOLDER = 'uploads'
if not os.path.exists(UPLOAD_FOLDER):
    os.makedirs(UPLOAD_FOLDER)

@app.route('/submit_video', methods=['POST'])
def submit_video():
    """
    REST API 接口：接收并保存上传的视频文件
    入参：视频文件（multipart/form-data）
    返回：JSON 格式的保存结果
    """
    try:
        # 检查是否有文件被上传
        if 'video' not in request.files:
            return jsonify({
                "status": "error",
                "message": "没有找到上传的视频文件"
            }), 400
        
        video_file = request.files['video']
        
        # 检查文件名是否为空
        if video_file.filename == '':
            return jsonify({
                "status": "error",
                "message": "未选择文件"
            }), 400
        
        # 保存文件，始终命名为 recorded_video.mp4
        video_path = os.path.join(UPLOAD_FOLDER, 'recorded_video.mp4')
        video_file.save(video_path)
        
        return jsonify({
            "status": "success",
            "message": "视频文件上传成功",
            "file_path": video_path
        }), 200
        
    except Exception as e:
        return jsonify({
            "status": "error",
            "message": f"处理视频上传时发生错误: {str(e)}"
        }), 500

@app.route('/get_video_description', methods=['GET'])
def get_video_description():
    """
    REST API 接口：获取视频内容的描述
    入参：无
    返回：JSON 格式的豆包 API 文本结果
    """
    try:
        # 假设视频文件路径固定
        video_path = "uploads/recorded_video.mp4"
        
        # 处理视频并获取结果
        result = processor.process_video(
            video_path=video_path,
            extraction_strategy=Strategy.CONSTANT_INTERVAL,
            interval_in_seconds=1,
            max_frames=20
        )
        
        # 检查结果是否包含错误
        if "error" in result:
            return jsonify({
                "status": "error",
                "message": result["error"]
            }), 500
        
        # 返回豆包 API 的文本描述
        return jsonify({
            "status": "success",
            "description": result["description"]
        }), 200
    
    except Exception as e:
        return jsonify({
            "status": "error",
            "message": f"处理视频时发生错误: {str(e)}"
        }), 500

if __name__ == "__main__":
    # 启动 Flask 服务，监听本地 5000 端口
    app.run(host="0.0.0.0", port=5000, debug=True)