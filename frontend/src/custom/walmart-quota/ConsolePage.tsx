import { useNavigate } from 'ice';
import WalmartQuota from './index';

export default function ConsoleQuotaPage({ page }: { page: string }) {
  const navigate = useNavigate();
  return <WalmartQuota consolePage page={page} onNavigate={(next: string) => navigate(`/walmart-quota/${next}`)} />;
}
