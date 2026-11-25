import { useEffect } from 'react';
import { useNavigate } from 'ice';

export default function WalmartQuotaPage() {
  const navigate = useNavigate();
  useEffect(() => { navigate('/walmart-quota/overview', { replace: true }); }, [navigate]);
  return null;
}
