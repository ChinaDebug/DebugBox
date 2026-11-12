import re
import os
import time
import requests
from lxml import etree
from abc import abstractmethod, ABCMeta
from importlib.machinery import SourceFileLoader
from base.localProxy import Proxy

try:
    import ujson as json
except ImportError:
    import json

_PATTERN_HTML_TAGS = re.compile(r'<.*?>')
_PATTERN_EMOJI = re.compile(r'[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]')

class Spider(metaclass=ABCMeta):
    _instance = None
    _session = None

    def __init__(self):
        self.extend = ''
        if Spider._session is None:
            Spider._session = requests.Session()
            adapter = requests.adapters.HTTPAdapter(
                pool_connections=10,
                pool_maxsize=20,
                max_retries=3
            )
            Spider._session.mount('http://', adapter)
            Spider._session.mount('https://', adapter)

    def __new__(cls, *args, **kwargs):
        if cls._instance:
            return cls._instance
        else:
            cls._instance = super().__new__(cls)
            return cls._instance

    @abstractmethod
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        pass

    def homeVideoContent(self):
        pass

    def categoryContent(self, tid, pg, filter, extend):
        pass

    def detailContent(self, ids):
        pass

    def searchContent(self, key, quick, pg="1"):
        pass

    def playerContent(self, flag, id, vipFlags):
        pass

    def liveContent(self, url):
        pass

    def localProxy(self, param):
        pass

    def isVideoFormat(self, url):
        pass

    def manualVideoCheck(self):
        pass

    def action(self, action):
        pass

    def destroy(self):
        pass

    def getName(self):
        pass

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        path = os.path.join(os.path.join("../plugin"),  f'{name}.py')
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        src = ''
        if m:
            src = m.group(group)
        return src

    def removeHtmlTags(self, src):
        return _PATTERN_HTML_TAGS.sub('', src)

    def cleanText(self, src):
        return _PATTERN_EMOJI.sub('', src)

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=5, verify=True, stream=False,
              allow_redirects=True):
        rsp = self._session.get(url, params=params, cookies=cookies, headers=headers, timeout=timeout, verify=verify,
                                stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def _clean_header_value(self, value):
        cleaned = value.strip()
        if cleaned.startswith("<!DOCTYPE html>"):
            cleaned = "DefaultValue"
        return cleaned

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects=True):
        if headers:
            headers = {k: self._clean_header_value(v) for k, v in headers.items()}
        rsp = self._session.post(url, params=params, data=data, json=json, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def html(self, content):
        return etree.HTML(content)

    def str2json(str):
        return json.loads(str)

    def json2str(str):
        return json.dumps(str, ensure_ascii=False)

    def getProxyUrl(self, local=True):
        return f'{Proxy.getUrl(self,local)}?do=py'

    def log(self, msg):
        if isinstance(msg, dict) or isinstance(msg, list):
            print(json.dumps(msg, ensure_ascii=False))
        else:
            print(f'{msg}')

    def getCache(self, key):
        value = self.fetch(f'http://127.0.0.1:{Proxy.getPort(self)}/cache?do=get&key={key}', timeout=5).text
        if len(value) > 0:
            if value.startswith('{') and value.endswith('}') or value.startswith('[') and value.endswith(']'):
                value = json.loads(value)
                if type(value) == dict:
                    if not 'expiresAt' in value or value['expiresAt'] >= int(time.time()):
                        return value
                    else:
                        self.delCache(key)
                        return None
            return value
        else:
            return None

    def setCache(self, key, value):
        if type(value) in [int, float]:
            value = str(value)
        if len(value) > 0:
            if type(value) == dict or type(value) == list:
                value = json.dumps(value, ensure_ascii=False)
        r = self.post(f'http://127.0.0.1:{Proxy.getPort(self)}/cache?do=set&key={key}', data={"value": value}, timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'

    def delCache(self, key):
        r = self.fetch(f'http://127.0.0.1:{Proxy.getPort(self)}/cache?do=del&key={key}', timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'
