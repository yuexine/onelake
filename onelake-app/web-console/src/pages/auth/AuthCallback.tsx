import { useEffect, useState } from 'react';
import { Result, Spin } from 'antd';
import { useNavigate } from 'react-router-dom';
import { handleLoginCallback, startLogin } from '../../auth/oidc';

export function AuthLogin() {
  useEffect(() => {
    void startLogin('/dashboard');
  }, []);

  return <Spin fullscreen tip="正在跳转到单点登录" />;
}

export default function AuthCallback() {
  const navigate = useNavigate();
  const [error, setError] = useState<string>();

  useEffect(() => {
    void handleLoginCallback(window.location.search)
      .then((returnTo) => navigate(returnTo, { replace: true }))
      .catch((err) => setError(err instanceof Error ? err.message : '登录失败'));
  }, [navigate]);

  if (error) {
    return (
      <Result
        status="error"
        title="登录失败"
        subTitle={error}
      />
    );
  }

  return <Spin fullscreen tip="正在完成登录" />;
}
