import { DatabaseOutlined, DesktopOutlined, DownOutlined, EditOutlined, EllipsisOutlined, GlobalOutlined, PictureOutlined, PlusOutlined } from '@ant-design/icons';
import {
  DingdingOutlined,
  FileTextOutlined,
  GithubOutlined,
  QuestionCircleOutlined,
  ReadOutlined,
  ToolOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { Button, Card, Col, Dropdown, Row, Space, Spin, Tooltip, Typography } from 'antd';
import classNames from 'classnames';
import { downloadText } from 'download.js';
import { useEffect, useState } from 'react';
import { history } from 'umi';
import AddOrEditProject from './components/AddOrEditProject';
import ImportProject from './components/ImportProject';
import DbConnectionModal from '@/components/DbConnectionModal';
import IconLibraryModal from '@/components/IconLibraryModal';
import TileConfigModal from '@/components/TileConfigModal';
import useStyle from './style';
import { logYuyanMonitor } from '@/utils';
import { deleteProject, getProjectList } from '@/services';
import type { Project as ProjectType } from '@/services';
import { DEFAULT_PROJECTS } from '@/constants';

const { Meta } = Card;

const hexToString = (hex: string): string => {
  if (!hex) return hex;
  // Java 后端存的已经是正常 URL，不需要 hex 解码
  if (hex.startsWith('/')) return hex;
  if (hex.startsWith('http')) return hex;
  if (hex.startsWith('0x')) {
    hex = hex.substring(2);
  }
  try {
    let result = '';
    for (let i = 0; i < hex.length; i += 2) {
      result += String.fromCharCode(parseInt(hex.substring(i, i + 2), 16));
    }
    return result;
  } catch {
    return hex;
  }
};

const Project = () => {
  const [loadingProjects, setLoadingProjects] = useState(true);
  const [addProjectVisible, setAddProjectVisible] = useState(false);
  const [importProjectVisible, setImportProjectVisible] = useState(false);
  const [type, setType] = useState<'add' | 'edit'>('add');
  const [editProject, setEditProject] = useState<ProjectType>();
  const [projectList, setProjectList] = useState<ProjectType[]>([]);
  const styles = useStyle();
  const [isProjectBtnOpen, setIsProjectBtnOpen] = useState(false);
  const [tileConfigVisible, setTileConfigVisible] = useState(false);
  const [iconLibraryVisible, setIconLibraryVisible] = useState(false);
  const [dbConnectionVisible, setDbConnectionVisible] = useState(false);

  const getProjects = () => {
    return getProjectList()
      .then((projects) => {
        const decodedProjects = projects.map((project) => ({
          ...project,
          thumbnail: hexToString(project.thumbnail),
        }));
        if (decodedProjects.length) {
          setProjectList(DEFAULT_PROJECTS.concat(decodedProjects));
        } else {
          setProjectList(DEFAULT_PROJECTS);
        }
      })
      .catch((err) => {
        console.log('err: ', err);
      });
  };

  useEffect(() => {
    setLoadingProjects(true);
    getProjects().finally(() => setLoadingProjects(false));
  }, []);

  const handleAddProject = () => {
    history.push(`/new`);
  };

  const handleRouteBuilder = (value: ProjectType) => {
    history.push({
      pathname: `/builder/${value.projectId}`,
    });
  };

  const onSubmit = (value: ProjectType) => {
    if (type === 'edit') {
      getProjects();
    } else {
      handleRouteBuilder(value);
    }
  };

  const onImprotSubmit = () => {
    getProjects();
  };

  // 根据项目ID生成固定的颜色
  const getProjectColor = (projectId: string) => {
    const hash = projectId.split('').reduce((acc, char) => acc + char.charCodeAt(0), 0);
    const hue = hash % 360;
    return `linear-gradient(135deg, hsl(${hue}, 70%, 80%), hsl(${(hue + 30) % 360}, 70%, 90%))`;
  };

  if (loadingProjects) {
    return (
      <div style={{ textAlign: 'center', padding: '60px 50px' }}>
        <Spin />
      </div>
    );
  }

  // 系统配置下拉菜单
  const settingMenuItems: MenuProps['items'] = [
    {
      key: '0',
      label: (<span onClick={() => setDbConnectionVisible(true)}>数据源配置</span>),
      icon: <DatabaseOutlined />,
    },
    {
      key: '1',
      label: (<span onClick={() => setIconLibraryVisible(true)}>图标库管理</span>),
      icon: <PictureOutlined />,
    },
    {
      key: '2',
      label: (<span onClick={() => setTileConfigVisible(true)}>瓦片配置</span>),
      icon: <GlobalOutlined />,
    },
  ];

  return (
    <div className={styles.project}>
      <div className={styles.projectHeader}>
        <h2 className={styles.projectTitle}>地理可视化项目</h2>
        <Space>
          <Dropdown.Button
            type="primary"
            icon={
              <DownOutlined
                className={classNames(styles.importBtnIcon, {
                  [styles.importBtnIconRotate]: isProjectBtnOpen,
                })}
              />
            }
            menu={{
              items: [
                {
                  key: 'importProject',
                  label: '导入项目',
                  onClick() {
                    setImportProjectVisible(true);
                  },
                },
              ],
            }}
            onClick={() => handleAddProject()}
            onOpenChange={(open) => {
              setIsProjectBtnOpen(open);
            }}
          >
            创建项目
          </Dropdown.Button>
          {/* <Button icon={<DatabaseOutlined />} onClick={() => setDbConnectionVisible(true)}>
            数据源
          </Button>
          <Button icon={<PictureOutlined />} onClick={() => setIconLibraryVisible(true)}>
            图标库
          </Button>
          <Button icon={<GlobalOutlined />} onClick={() => setTileConfigVisible(true)}>
            瓦片配置
          </Button> */}

          <Dropdown menu={{ items: settingMenuItems }} placement="bottomRight">
            <Button
              type="text"
              size="middle"
              icon={<SettingOutlined />}
            >
              系统配置
            </Button>
          </Dropdown>
        </Space>
      </div>
      <Row gutter={[48, 24]}>
        {projectList.map((item) => {
          const dropDownItems: MenuProps['items'] = [
            {
              key: '2',
              label: '导出项目',
              onClick() {
                downloadText(`${item.projectName}.json`, JSON.stringify(item.applicationConfig));
              },
            },
            {
              key: '3',
              label: '删除项目',
              onClick() {
                deleteProject(item.projectId).then(() => {
                  getProjects();
                });
              },
            },
          ];

          const card = (
            <Card
              className={styles.projectCard}
              onClick={() => handleRouteBuilder(item)}
              cover={
                <div>
                  {item.thumbnail ? (
                    <img
                      className={styles.projectCardImg}
                      src={item.thumbnail}
                      alt={item.projectName}
                    />
                  ) : (
                    <div
                      className={styles.projectCardImg}
                      style={{
                        background: getProjectColor(item.projectId),
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '48px',
                        color: 'rgba(0,0,0,0.3)',
                        fontWeight: 'bold',
                      }}
                    >
                      {item.projectName?.charAt(0) || 'P'}
                    </div>
                  )}
                  <div className={styles.projectCardTools} onClick={(e) => e.stopPropagation()}>
                    <Space>
                      <Tooltip title="预览项目">
                        <Button
                          type="text"
                          shape="circle"
                          icon={<DesktopOutlined />}
                          onClick={() => {
                            logYuyanMonitor(11);
                            history.push(`/app/${item.projectId}?type=project`);
                          }}
                        />
                      </Tooltip>
                      <Tooltip title="修改项目">
                        <Button
                          type="text"
                          shape="circle"
                          icon={<EditOutlined />}
                          onClick={() => {
                            setType('edit');
                            setEditProject(item);
                            setAddProjectVisible(true);
                          }}
                        />
                      </Tooltip>
                      <Dropdown menu={{ items: dropDownItems }}>
                        <Button type="text" shape="circle" icon={<EllipsisOutlined />} />
                      </Dropdown>
                    </Space>
                  </div>
                </div>
              }
            >
              <Meta
                title={<span title={item.projectName}>{item.projectName}</span>}
                description={
                  <div>
                    <Typography.Paragraph
                      className={styles.itemDescription}
                      type="secondary"
                      ellipsis={{
                        rows: 1,
                        tooltip: item.description?.length > 20 && { title: item.description },
                      }}
                    >
                      {item.description ?? '暂无项目描述'}
                    </Typography.Paragraph>
                  </div>
                }
              />
            </Card>
          );

          return (
            <Col key={item.projectId} xxl={6} xl={8} lg={12} md={12} sm={24} xs={24}>
              {card}
            </Col>
          );
        })}

        {projectList.length === 0 && (
          <Col xxl={6} xl={8} lg={12} md={12} sm={24} xs={24}>
            <Card bordered={false} className={styles.projectCard} onClick={handleAddProject}>
              <div className={styles.addCard}>
                <PlusOutlined className={styles.addCardIcon} />
                <span>暂无项目，创建一个吧</span>
              </div>
            </Card>
          </Col>
        )}
      </Row>
      <AddOrEditProject
        visible={addProjectVisible}
        onVisibleChange={setAddProjectVisible}
        project={editProject}
        type={type}
        onSubmit={onSubmit}
      />
      <ImportProject
        visible={importProjectVisible}
        onVisibleChange={setImportProjectVisible}
        onSubmit={onImprotSubmit}
      />
      <IconLibraryModal
        visible={iconLibraryVisible}
        onVisibleChange={setIconLibraryVisible}
      />
      <DbConnectionModal
        visible={dbConnectionVisible}
        onVisibleChange={setDbConnectionVisible}
      />
      <TileConfigModal
        visible={tileConfigVisible}
        onVisibleChange={setTileConfigVisible}
      />
    </div>
  );
};

export default Project;
