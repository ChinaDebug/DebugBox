class Proxy:
    def getUrl(self, local):
        # 本地代理路由挂在 /proxy 下（对应 RemoteServer 的 /proxy?do=xxx 处理），
        # 必须带路径，否则爬虫拼接的代理 URL（如图片 http://127.0.0.1:9978?do=py&url=..&type=img）
        # 会请求根路径返回静态页面，导致分类/搜索海报等代理资源加载失败。
        return 'http://127.0.0.1:9978/proxy'

    def getPort(self):
        return 9978
