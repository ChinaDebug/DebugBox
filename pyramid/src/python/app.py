#coding=utf-8
#!/usr/bin/python
import os
import sys
import requests
import hashlib
import marshal
import types
from importlib.machinery import SourceFileLoader
from urllib import parse

sys.dont_write_bytecode = True

try:
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad, unpad
    CRYPTO_AVAILABLE = True
except ImportError:
    CRYPTO_AVAILABLE = False

try:
    import ujson as json
except ImportError:
    import json

_session = None

def _get_session():
    global _session
    if _session is None:
        _session = requests.Session()
        adapter = requests.adapters.HTTPAdapter(
            pool_connections=5,
            pool_maxsize=10,
            max_retries=3
        )
        _session.mount('http://', adapter)
        _session.mount('https://', adapter)
    return _session

def createFile(file_path):
    if not os.path.exists(file_path):
        os.makedirs(file_path)

def redirectResponse(tUrl):
    session = _get_session()
    rsp = session.get(tUrl, allow_redirects=False, verify=False)
    if 'Location' in rsp.headers:
        return redirectResponse(rsp.headers['Location'])
    return rsp

def derive_key(name):
    seed = f"com.github.tvbox.osc:{name}:v1"
    return hashlib.sha256(seed.encode()).digest()[:16]

def encrypt_data(data, key):
    if not CRYPTO_AVAILABLE:
        return data
    try:
        cipher = AES.new(key, AES.MODE_CBC, iv=key)
        padded = pad(data, AES.block_size)
        return cipher.encrypt(padded)
    except Exception as e:
        pass
        return data

def decrypt_data(data, key):
    if not CRYPTO_AVAILABLE:
        return data
    try:
        cipher = AES.new(key, AES.MODE_CBC, iv=key)
        decrypted = cipher.decrypt(data)
        return unpad(decrypted, AES.block_size)
    except Exception as e:
        pass
        return None

def get_cache_filename(basePath, name):
    hash_val = hashlib.md5(f"spider_{name}_2024".encode()).hexdigest()[:16]
    return os.path.join(basePath, f"cache_{hash_val}.cache")

def get_key_filename(cache_file):
    base_dir = os.path.dirname(cache_file)
    filename = os.path.basename(cache_file).replace('.cache', '.idx')
    idx_dir = os.path.join(base_dir, 'idx')
    return os.path.join(idx_dir, filename)

def downloadFile(name, url):
    try:
        rsp = redirectResponse(url)
        with open(name, 'wb') as f:
            f.write(rsp.content)
        print(url)
    except Exception as e:
        print(f'{name} =======================================> error: {e}')
        print(url)

def downloadPlugin(basePath, url):
    createFile(basePath)
    name = url.split('/')[-1].split('.')[0]
    
    # 获取缓存文件路径
    cache_file = get_cache_filename(basePath, name)
    
    if url.startswith('file://'):
        # 本地文件直接加载
        pyName = url.replace('file://', '')
        gParam['SpiderPath'][name] = pyName
    else:
        # 每次重新下载（不检查缓存）
        try:
            rsp = redirectResponse(url)
            data = rsp.content
            if url.endswith('.pyc') or data[:2] == b'\x55\x0d':
                pyc_data = data
            else:
                try:
                    code_obj = compile(data.decode('utf-8'), '<string>', 'exec')
                    pyc_data = marshal.dumps(code_obj)
                except:
                    pyc_data = data
            
            key = os.urandom(16)
            encrypted = encrypt_data(pyc_data, key)
            key_file = get_key_filename(cache_file)
            if os.path.exists(cache_file):
                os.remove(cache_file)
            if os.path.exists(key_file):
                os.remove(key_file)
            idx_dir = os.path.dirname(key_file)
            if not os.path.exists(idx_dir):
                os.makedirs(idx_dir)
            with open(key_file, 'wb') as f:
                f.write(key)
            with open(cache_file, 'wb') as f:
                f.write(encrypted)
            
            gParam['SpiderPath'][name] = cache_file
        except Exception as e:
            raise
    
    paramList = parse.parse_qs(parse.urlparse(url).query).get('extend')
    gParam['SpiderParam'][name] = paramList[0] if paramList else ''
    return gParam['SpiderPath'][name]

def loadFromDisk(fileName):
    name = fileName.split('/')[-1].split('.')[0]
    spList = gParam['SpiderList']
    if name not in spList:
        if 'cache_' in fileName and fileName.endswith('.cache'):
            try:
                key_file = get_key_filename(fileName)
                with open(key_file, 'rb') as f:
                    key = f.read()

                with open(fileName, 'rb') as f:
                    encrypted = f.read()
                
                pyc_data = decrypt_data(encrypted, key)
                if pyc_data is None:
                    raise Exception("Decrypt failed")
                
                # 尝试作为 marshal 加载
                try:
                    code_obj = marshal.loads(pyc_data)
                except:
                    # 不是 marshal，作为普通代码执行
                    code_obj = compile(pyc_data.decode('utf-8'), '<string>', 'exec')
                
                # 创建模块并执行
                module = types.ModuleType(name)
                exec(code_obj, module.__dict__)
                sp = module.Spider()
                spList[name] = sp
            except Exception as e:
                raise
        else:
            # 普通文件加载
            sp = SourceFileLoader(name, fileName).load_module().Spider()
            spList[name] = sp
    return spList[name]

def str2json(content):
    return json.loads(content)

gParam = {
    "SpiderList": {},
    "SpiderPath": {},
    "SpiderParam": {}
}

def getDependence(ru):
    result = ru.getDependence()
    return result

def getName(ru):
    result = ru.getName()
    return result

def init(ru,extend):
    spoList = []
    spList = gParam['SpiderList']
    sPath = gParam['SpiderPath']
    sParam = gParam['SpiderParam']
    for key in ru.getDependence():
        sp = None
        if key in spList.keys():
            sp = spList[key]
        elif key in sPath.keys():
            sp = loadFromDisk(sPath[key])
        if sp != None:
            sp.setExtendInfo(sParam[key])
            spoList.append(sp)
    ru.init(extend)

def homeContent(ru,filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def categoryContent(ru,tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def detailContent(ru,array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def playerContent(ru,flag,id,vipFlags):
    result = ru.playerContent(flag,id,str2json(vipFlags))
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def liveContent(ru,url):
    result = ru.liveContent(url)
    return result

def searchContent(ru,key,quick):
    result = ru.searchContent(key,quick)
    formatJo = json.dumps(result,ensure_ascii=False)
    return formatJo

def localProxy(ru,param):
    result = ru.localProxy(str2json(param))
    return result

def run():
    pass

if __name__ == '__main__':
    run()
